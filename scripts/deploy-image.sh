#!/bin/bash

# Deploy to AWS ECS Fargate Script for ResortsLite Application
# This script deploys the Docker image to AWS ECS Fargate

set -e
set -o pipefail

echo "=========================================="
echo "ResortsLite - AWS ECS Fargate Deployment"
echo "=========================================="
echo ""

# Prompt for AWS Region
read -p "Enter AWS Region (e.g., us-east-1): " AWS_REGION
export AWS_DEFAULT_REGION="$AWS_REGION"

# Get AWS Account ID
echo "Retrieving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "AWS Account ID: $ACCOUNT_ID"
echo ""

# Prompt for ECS Cluster Name
read -p "Enter ECS Cluster Name: " CLUSTER_NAME

# Check if cluster exists, create if not
echo "Checking if ECS cluster exists..."
aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null 2>&1 || {
    echo "Cluster does not exist. Creating ECS cluster: $CLUSTER_NAME"
    aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
    echo "ECS cluster created successfully"
}
echo ""

# Prompt for VPC and Network Configuration
echo "=== Network Configuration ==="
read -p "Enter VPC ID: " VPC_ID
read -p "Enter Subnet ID 1: " SUBNET_1
read -p "Enter Subnet ID 2: " SUBNET_2
read -p "Enter Security Group ID: " SECURITY_GROUP
echo ""

# Prompt for Docker Image URI
read -p "Enter Docker Image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
echo ""

# Prompt for Redis Configuration
echo "=== Redis Configuration ==="
read -p "Enter Redis Host (e.g., redis.example.com): " REDIS_HOST
read -p "Enter Redis Port (default: 6379): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}
read -sp "Enter Redis Password (leave empty if none): " REDIS_PASSWORD
echo ""
echo ""

# Prompt for S3 Configuration
echo "=== S3 Configuration ==="
read -p "Enter S3 Bucket Name: " S3_BUCKET_NAME
echo ""

# Prompt for Notification Endpoint
echo "=== Notification Service Configuration ==="
read -p "Enter Notification Endpoint (default: http://notify.internal:7070/send): " APP_NOTIFICATION_ENDPOINT
APP_NOTIFICATION_ENDPOINT=${APP_NOTIFICATION_ENDPOINT:-http://notify.internal:7070/send}
echo ""

# Ask about Load Balancer
read -p "Do you need a load balancer for this service? (y/n): " NEED_LB

if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
    echo ""
    echo "=== Creating Application Load Balancer and Target Group ==="
    
    # Create Target Group
    echo "Creating Target Group..."
    TARGET_GROUP_NAME="resortslite-tg-$(date +%s)"
    
    TG_ARN=$(aws elbv2 create-target-group \
        --name "$TARGET_GROUP_NAME" \
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
        --output text)
    
    echo "Target Group created: $TG_ARN"
    
    # Create Application Load Balancer
    echo "Creating Application Load Balancer..."
    ALB_NAME="resortslite-alb-$(date +%s)"
    
    ALB_ARN=$(aws elbv2 create-load-balancer \
        --name "$ALB_NAME" \
        --subnets "$SUBNET_1" "$SUBNET_2" \
        --security-groups "$SECURITY_GROUP" \
        --scheme internet-facing \
        --type application \
        --ip-address-type ipv4 \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].LoadBalancerArn' \
        --output text)
    
    echo "Application Load Balancer created: $ALB_ARN"
    
    # Get ALB DNS Name
    ALB_DNS=$(aws elbv2 describe-load-balancers \
        --load-balancer-arns "$ALB_ARN" \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].DNSName' \
        --output text)
    
    echo "ALB DNS Name: $ALB_DNS"
    
    # Create Listener
    echo "Creating ALB Listener..."
    aws elbv2 create-listener \
        --load-balancer-arn "$ALB_ARN" \
        --protocol HTTP \
        --port 80 \
        --default-actions Type=forward,TargetGroupArn="$TG_ARN" \
        --region "$AWS_REGION" >/dev/null
    
    echo "ALB Listener created successfully"
    echo ""
    
    TARGET_GROUP_ARN="$TG_ARN"
else
    echo ""
    echo "Skipping load balancer creation. Service will be deployed without load balancer."
    echo ""
    TARGET_GROUP_ARN=""
fi

# Create CloudWatch Log Group
echo "Creating CloudWatch Log Group..."
aws logs create-log-group --log-group-name "/ecs/resortslite" --region "$AWS_REGION" 2>/dev/null || echo "Log group already exists"
echo ""

# Replace placeholders in task definition
echo "Preparing ECS Task Definition..."
TASK_DEF_FILE="ecs/task-definition.json"
TASK_DEF_TEMP="ecs/task-definition-temp.json"

cp "$TASK_DEF_FILE" "$TASK_DEF_TEMP"

sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" "$TASK_DEF_TEMP"
sed -i "s|{{ACCOUNT_ID}}|$ACCOUNT_ID|g" "$TASK_DEF_TEMP"
sed -i "s|{{AWS_REGION}}|$AWS_REGION|g" "$TASK_DEF_TEMP"
sed -i "s|{{REDIS_HOST}}|$REDIS_HOST|g" "$TASK_DEF_TEMP"
sed -i "s|{{REDIS_PORT}}|$REDIS_PORT|g" "$TASK_DEF_TEMP"
sed -i "s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g" "$TASK_DEF_TEMP"
sed -i "s|{{S3_BUCKET_NAME}}|$S3_BUCKET_NAME|g" "$TASK_DEF_TEMP"
sed -i "s|{{APP_NOTIFICATION_ENDPOINT}}|$APP_NOTIFICATION_ENDPOINT|g" "$TASK_DEF_TEMP"

# Register Task Definition
echo "Registering ECS Task Definition..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
    --cli-input-json file://"$TASK_DEF_TEMP" \
    --region "$AWS_REGION" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)

echo "Task Definition registered: $TASK_DEF_ARN"
echo ""

# Clean up temp file
rm -f "$TASK_DEF_TEMP"

# Prepare service definition
echo "Preparing ECS Service Definition..."
SERVICE_DEF_FILE="ecs/service-definition.json"
SERVICE_DEF_TEMP="ecs/service-definition-temp.json"

cp "$SERVICE_DEF_FILE" "$SERVICE_DEF_TEMP"

sed -i "s|{{CLUSTER_NAME}}|$CLUSTER_NAME|g" "$SERVICE_DEF_TEMP"
sed -i "s|{{SUBNET_1}}|$SUBNET_1|g" "$SERVICE_DEF_TEMP"
sed -i "s|{{SUBNET_2}}|$SUBNET_2|g" "$SERVICE_DEF_TEMP"
sed -i "s|{{SECURITY_GROUP}}|$SECURITY_GROUP|g" "$SERVICE_DEF_TEMP"

if [ -z "$TARGET_GROUP_ARN" ]; then
    # Remove loadBalancers section if no load balancer
    sed -i '/"loadBalancers":/,/],/d' "$SERVICE_DEF_TEMP"
    sed -i '/"healthCheckGracePeriodSeconds":/d' "$SERVICE_DEF_TEMP"
else
    sed -i "s|{{TARGET_GROUP_ARN}}|$TARGET_GROUP_ARN|g" "$SERVICE_DEF_TEMP"
fi

# Check if service exists
SERVICE_NAME="resortslite-service"
echo "Checking if ECS service exists..."
EXISTING_SERVICE=$(aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[?status==`ACTIVE`].serviceName' \
    --output text 2>/dev/null || echo "")

if [ -z "$EXISTING_SERVICE" ]; then
    # Create new service
    echo "Creating new ECS service..."
    aws ecs create-service \
        --cli-input-json file://"$SERVICE_DEF_TEMP" \
        --region "$AWS_REGION" >/dev/null
    
    echo "ECS service created successfully"
else
    # Update existing service
    echo "Updating existing ECS service..."
    aws ecs update-service \
        --cluster "$CLUSTER_NAME" \
        --service "$SERVICE_NAME" \
        --task-definition "$TASK_DEF_ARN" \
        --desired-count 2 \
        --region "$AWS_REGION" >/dev/null
    
    echo "ECS service updated successfully"
fi

echo ""

# Clean up temp file
rm -f "$SERVICE_DEF_TEMP"

# Wait for service to stabilize
echo "Waiting for service to become stable (this may take a few minutes)..."
aws ecs wait services-stable \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION"

echo ""
echo "=========================================="
echo "DEPLOYMENT SUCCESSFUL!"
echo "=========================================="
echo ""

# Display service information
echo "Service Details:"
aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[0].[serviceName,status,runningCount,desiredCount]' \
    --output table

echo ""
echo "CloudWatch Logs:"
echo "  Log Group: /ecs/resortslite"
echo "  Region: $AWS_REGION"
echo "  View logs: https://console.aws.amazon.com/cloudwatch/home?region=$AWS_REGION#logsV2:log-groups/log-group/\$252Fecs\$252Fresortslite"
echo ""

if [ -n "$TARGET_GROUP_ARN" ]; then
    echo "Application Load Balancer:"
    echo "  DNS Name: $ALB_DNS"
    echo "  Access your application at: http://$ALB_DNS"
    echo ""
fi

echo "Deployment completed successfully!"
echo "=========================================="
