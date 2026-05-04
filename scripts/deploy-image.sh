#!/bin/bash
set -e
set -o pipefail

# Deploy ResortsLite to AWS ECS Fargate
# This script deploys the Docker image to AWS ECS Fargate

echo "=========================================="
echo "ResortsLite - AWS ECS Fargate Deployment"
echo "=========================================="
echo ""

# Prompt for AWS configuration
read -p "Enter AWS Region (e.g., us-east-1): " AWS_REGION
read -p "Enter ECS Cluster Name: " CLUSTER_NAME
read -p "Enter Docker Image URI: " IMAGE_URI

echo ""
echo "=== Network Configuration ==="
read -p "Enter VPC ID: " VPC_ID
read -p "Enter Subnet IDs (comma-separated, at least 2): " SUBNETS_INPUT
read -p "Enter Security Group ID: " SECURITY_GROUP

# Convert comma-separated subnets to array
IFS=',' read -ra SUBNETS <<< "$SUBNETS_INPUT"
SUBNET_1="${SUBNETS[0]}"
SUBNET_2="${SUBNETS[1]:-$SUBNET_1}"

echo ""
echo "=== External Service Configuration ==="
read -p "Enter Redis Host: " REDIS_HOST
read -p "Enter Redis Port (default: 6379): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -sp "Enter Redis Password (leave empty if none): " REDIS_PASSWORD
echo ""
read -p "Enter S3 Bucket Name: " S3_BUCKET_NAME
read -p "Enter Inventory Service URL: " INVENTORY_SERVICE_URL

echo ""
read -p "Do you need a load balancer for this service? (y/n): " NEED_LB

# Get AWS Account ID
echo ""
echo "Getting AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: $ACCOUNT_ID"

# Check if ECS cluster exists, create if not
echo ""
echo "Checking ECS cluster..."
aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null 2>&1 || {
    echo "Creating ECS cluster: $CLUSTER_NAME"
    aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
}

# Create CloudWatch log group
echo ""
echo "Creating CloudWatch log group..."
aws logs create-log-group --log-group-name "/ecs/resortslite" --region "$AWS_REGION" 2>/dev/null || echo "Log group already exists"

# Handle Load Balancer
if [[ "$NEED_LB" == "y" || "$NEED_LB" == "Y" ]]; then
    echo ""
    echo "=== Creating Application Load Balancer ==="
    
    # Create ALB
    ALB_NAME="resortslite-alb"
    echo "Creating Application Load Balancer: $ALB_NAME"
    ALB_ARN=$(aws elbv2 create-load-balancer \
        --name "$ALB_NAME" \
        --subnets "$SUBNET_1" "$SUBNET_2" \
        --security-groups "$SECURITY_GROUP" \
        --scheme internet-facing \
        --type application \
        --ip-address-type ipv4 \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].LoadBalancerArn' \
        --output text 2>/dev/null || aws elbv2 describe-load-balancers --names "$ALB_NAME" --region "$AWS_REGION" --query 'LoadBalancers[0].LoadBalancerArn' --output text)
    
    echo "ALB ARN: $ALB_ARN"
    
    # Create Target Group with target-type ip (required for Fargate)
    TG_NAME="resortslite-tg"
    echo "Creating Target Group: $TG_NAME"
    TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
        --name "$TG_NAME" \
        --protocol HTTP \
        --port 8080 \
        --vpc-id "$VPC_ID" \
        --target-type ip \
        --health-check-enabled \
        --health-check-protocol HTTP \
        --health-check-path "/actuator/health" \
        --health-check-interval-seconds 30 \
        --health-check-timeout-seconds 5 \
        --healthy-threshold-count 2 \
        --unhealthy-threshold-count 3 \
        --region "$AWS_REGION" \
        --query 'TargetGroups[0].TargetGroupArn' \
        --output text 2>/dev/null || aws elbv2 describe-target-groups --names "$TG_NAME" --region "$AWS_REGION" --query 'TargetGroups[0].TargetGroupArn' --output text)
    
    echo "Target Group ARN: $TARGET_GROUP_ARN"
    
    # Create Listener
    echo "Creating ALB Listener..."
    aws elbv2 create-listener \
        --load-balancer-arn "$ALB_ARN" \
        --protocol HTTP \
        --port 80 \
        --default-actions Type=forward,TargetGroupArn="$TARGET_GROUP_ARN" \
        --region "$AWS_REGION" 2>/dev/null || echo "Listener already exists"
    
    # Get ALB DNS name
    ALB_DNS=$(aws elbv2 describe-load-balancers --load-balancer-arns "$ALB_ARN" --region "$AWS_REGION" --query 'LoadBalancers[0].DNSName' --output text)
    echo "ALB DNS Name: $ALB_DNS"
else
    echo ""
    echo "Skipping load balancer creation..."
    TARGET_GROUP_ARN=""
fi

# Replace placeholders in task definition
echo ""
echo "Preparing task definition..."
cp ecs/task-definition.json ecs/task-definition-temp.json

sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" ecs/task-definition-temp.json
sed -i "s|{{AWS_REGION}}|$AWS_REGION|g" ecs/task-definition-temp.json
sed -i "s|{{ACCOUNT_ID}}|$ACCOUNT_ID|g" ecs/task-definition-temp.json
sed -i "s|{{REDIS_HOST}}|$REDIS_HOST|g" ecs/task-definition-temp.json
sed -i "s|{{REDIS_PORT}}|$REDIS_PORT|g" ecs/task-definition-temp.json
sed -i "s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g" ecs/task-definition-temp.json
sed -i "s|{{S3_BUCKET_NAME}}|$S3_BUCKET_NAME|g" ecs/task-definition-temp.json
sed -i "s|{{INVENTORY_SERVICE_URL}}|$INVENTORY_SERVICE_URL|g" ecs/task-definition-temp.json

# Register task definition
echo "Registering task definition..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
    --cli-input-json file://ecs/task-definition-temp.json \
    --region "$AWS_REGION" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)

echo "Task Definition ARN: $TASK_DEF_ARN"

# Prepare service definition
echo ""
echo "Preparing service definition..."
cp ecs/service-definition.json ecs/service-definition-temp.json

sed -i "s|{{CLUSTER_NAME}}|$CLUSTER_NAME|g" ecs/service-definition-temp.json
sed -i "s|{{SUBNET_1}}|$SUBNET_1|g" ecs/service-definition-temp.json
sed -i "s|{{SUBNET_2}}|$SUBNET_2|g" ecs/service-definition-temp.json
sed -i "s|{{SECURITY_GROUP}}|$SECURITY_GROUP|g" ecs/service-definition-temp.json

if [[ -n "$TARGET_GROUP_ARN" ]]; then
    sed -i "s|{{TARGET_GROUP_ARN}}|$TARGET_GROUP_ARN|g" ecs/service-definition-temp.json
else
    # Remove loadBalancers section if no load balancer
    sed -i '/"loadBalancers":/,/],/d' ecs/service-definition-temp.json
    sed -i '/"healthCheckGracePeriodSeconds":/d' ecs/service-definition-temp.json
fi

# Check if service exists
SERVICE_NAME="resortslite-service"
EXISTING_SERVICE=$(aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[?status==`ACTIVE`].serviceName' \
    --output text 2>/dev/null || echo "")

if [[ -z "$EXISTING_SERVICE" || "$EXISTING_SERVICE" == "None" ]]; then
    echo ""
    echo "Creating new ECS service..."
    aws ecs create-service \
        --cli-input-json file://ecs/service-definition-temp.json \
        --region "$AWS_REGION"
else
    echo ""
    echo "Updating existing ECS service..."
    aws ecs update-service \
        --cluster "$CLUSTER_NAME" \
        --service "$SERVICE_NAME" \
        --task-definition "$TASK_DEF_ARN" \
        --desired-count 2 \
        --region "$AWS_REGION"
fi

# Wait for service stability
echo ""
echo "Waiting for service to become stable..."
aws ecs wait services-stable \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION"

# Verify deployment
echo ""
echo "=========================================="
echo "Deployment Status"
echo "=========================================="
aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[0].[serviceName,status,runningCount,desiredCount]' \
    --output table

echo ""
echo "=========================================="
echo "Deployment Completed Successfully!"
echo "=========================================="
echo "Cluster: $CLUSTER_NAME"
echo "Service: $SERVICE_NAME"
echo "Task Definition: $TASK_DEF_ARN"
if [[ -n "$ALB_DNS" ]]; then
    echo "Application URL: http://$ALB_DNS"
fi
echo "CloudWatch Logs: /ecs/resortslite"
echo ""
echo "To view logs:"
echo "aws logs tail /ecs/resortslite --follow --region $AWS_REGION"
echo ""

# Cleanup temporary files
rm -f ecs/task-definition-temp.json ecs/service-definition-temp.json
