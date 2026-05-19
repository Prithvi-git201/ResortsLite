#!/bin/bash
set -e
set -o pipefail

# Deploy to AWS ECS Fargate Script for ResortsLite Application
# This script deploys the Docker image to AWS ECS Fargate

echo "=========================================="
echo "ResortsLite - Deploy to AWS ECS Fargate"
echo "=========================================="
echo ""

# Prompt for AWS region
read -p "Enter AWS region (default: us-east-1): " AWS_REGION
AWS_REGION=${AWS_REGION:-us-east-1}

echo "Using AWS region: $AWS_REGION"
echo ""

# Get AWS Account ID
echo "=== Getting AWS Account ID ==="
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

if [ -z "$ACCOUNT_ID" ]; then
    echo "Error: Failed to get AWS account ID. Please check your AWS credentials."
    exit 1
fi

echo "AWS Account ID: $ACCOUNT_ID"
echo ""

# Prompt for ECS cluster name
read -p "Enter ECS cluster name (default: resortslite-cluster): " CLUSTER_NAME
CLUSTER_NAME=${CLUSTER_NAME:-resortslite-cluster}

echo "Using cluster: $CLUSTER_NAME"
echo ""

# Check if cluster exists, create if it doesn't
echo "=== Checking ECS Cluster ==="
aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" >/dev/null 2>&1 || {
    echo "Cluster does not exist. Creating ECS cluster: $CLUSTER_NAME"
    aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
    echo "ECS cluster created successfully"
}
echo ""

# Prompt for VPC configuration
echo "=== Network Configuration ==="
read -p "Enter VPC ID: " VPC_ID

if [ -z "$VPC_ID" ]; then
    echo "Error: VPC ID is required"
    exit 1
fi

# Prompt for subnets (comma-separated)
read -p "Enter subnet IDs (comma-separated, at least 2): " SUBNETS_INPUT

if [ -z "$SUBNETS_INPUT" ]; then
    echo "Error: At least 2 subnet IDs are required for high availability"
    exit 1
fi

# Convert comma-separated subnets to array
IFS=',' read -ra SUBNETS_ARRAY <<< "$SUBNETS_INPUT"
SUBNET_1=$(echo "${SUBNETS_ARRAY[0]}" | xargs)
SUBNET_2=$(echo "${SUBNETS_ARRAY[1]}" | xargs)

if [ -z "$SUBNET_1" ] || [ -z "$SUBNET_2" ]; then
    echo "Error: At least 2 subnet IDs are required"
    exit 1
fi

echo "Using subnets: $SUBNET_1, $SUBNET_2"
echo ""

# Prompt for security group
read -p "Enter security group ID (must allow inbound traffic on port 8080): " SECURITY_GROUP

if [ -z "$SECURITY_GROUP" ]; then
    echo "Error: Security group ID is required"
    exit 1
fi

echo "Using security group: $SECURITY_GROUP"
echo ""

# Prompt for Docker image URI
read -p "Enter Docker image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI

if [ -z "$IMAGE_URI" ]; then
    echo "Error: Docker image URI is required"
    exit 1
fi

echo "Using image: $IMAGE_URI"
echo ""

# Prompt for load balancer
read -p "Do you need a load balancer for this service? (y/n): " NEED_LB

if [ "$NEED_LB" = "y" ] || [ "$NEED_LB" = "Y" ]; then
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
        --output text)
    
    if [ -z "$ALB_ARN" ]; then
        echo "Error: Failed to create Application Load Balancer"
        exit 1
    fi
    
    echo "ALB created: $ALB_ARN"
    
    # Wait for ALB to be active
    echo "Waiting for ALB to become active..."
    aws elbv2 wait load-balancer-available --load-balancer-arns "$ALB_ARN" --region "$AWS_REGION"
    
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
        --output text)
    
    if [ -z "$TARGET_GROUP_ARN" ]; then
        echo "Error: Failed to create Target Group"
        exit 1
    fi
    
    echo "Target Group created: $TARGET_GROUP_ARN"
    
    # Create Listener
    echo "Creating ALB Listener on port 80"
    
    LISTENER_ARN=$(aws elbv2 create-listener \
        --load-balancer-arn "$ALB_ARN" \
        --protocol HTTP \
        --port 80 \
        --default-actions Type=forward,TargetGroupArn="$TARGET_GROUP_ARN" \
        --region "$AWS_REGION" \
        --query 'Listeners[0].ListenerArn' \
        --output text)
    
    if [ -z "$LISTENER_ARN" ]; then
        echo "Error: Failed to create Listener"
        exit 1
    fi
    
    echo "Listener created: $LISTENER_ARN"
    
    # Get ALB DNS name
    ALB_DNS=$(aws elbv2 describe-load-balancers \
        --load-balancer-arns "$ALB_ARN" \
        --region "$AWS_REGION" \
        --query 'LoadBalancers[0].DNSName' \
        --output text)
    
    echo "ALB DNS Name: $ALB_DNS"
    echo ""
    
    USE_LB="true"
else
    echo "Skipping load balancer creation"
    TARGET_GROUP_ARN=""
    USE_LB="false"
fi

# Prompt for application environment variables
echo "=== Application Configuration ==="
echo "Using existing configuration from application.properties"
echo ""

read -p "Enter Redis host (default: redis.example.com): " REDIS_HOST
REDIS_HOST=${REDIS_HOST:-redis.example.com}

read -p "Enter Redis port (default: 6379): " REDIS_PORT
REDIS_PORT=${REDIS_PORT:-6379}

read -p "Enter Redis password (leave empty if none): " REDIS_PASSWORD

read -p "Enter S3 reports bucket name (default: resort-reports-bucket): " S3_REPORTS_BUCKET
S3_REPORTS_BUCKET=${S3_REPORTS_BUCKET:-resort-reports-bucket}

read -p "Enter S3 backups bucket name (default: resort-backups-bucket): " S3_BACKUPS_BUCKET
S3_BACKUPS_BUCKET=${S3_BACKUPS_BUCKET:-resort-backups-bucket}

read -p "Enter Payment API URL (default: https://payment-service:9090/charge): " PAYMENT_API_URL
PAYMENT_API_URL=${PAYMENT_API_URL:-https://payment-service:9090/charge}

read -p "Enter Inventory Service URL (default: https://inventory-service:8081/rooms): " INVENTORY_SERVICE_URL
INVENTORY_SERVICE_URL=${INVENTORY_SERVICE_URL:-https://inventory-service:8081/rooms}

read -p "Enter Notification Service URL (default: https://notify-service:7070/send): " NOTIFICATION_SERVICE_URL
NOTIFICATION_SERVICE_URL=${NOTIFICATION_SERVICE_URL:-https://notify-service:7070/send}

echo ""

# Create CloudWatch log group
echo "=== Creating CloudWatch Log Group ==="
LOG_GROUP="/ecs/resortslite"

aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || echo "Log group already exists"
echo "CloudWatch log group: $LOG_GROUP"
echo ""

# Replace placeholders in task definition
echo "=== Preparing Task Definition ==="
cp ecs/task-definition.json ecs/task-definition-temp.json

sed -i "s|{{IMAGE_URI}}|$IMAGE_URI|g" ecs/task-definition-temp.json
sed -i "s|{{ACCOUNT_ID}}|$ACCOUNT_ID|g" ecs/task-definition-temp.json
sed -i "s|{{AWS_REGION}}|$AWS_REGION|g" ecs/task-definition-temp.json
sed -i "s|{{REDIS_HOST}}|$REDIS_HOST|g" ecs/task-definition-temp.json
sed -i "s|{{REDIS_PORT}}|$REDIS_PORT|g" ecs/task-definition-temp.json
sed -i "s|{{REDIS_PASSWORD}}|$REDIS_PASSWORD|g" ecs/task-definition-temp.json
sed -i "s|{{S3_REPORTS_BUCKET}}|$S3_REPORTS_BUCKET|g" ecs/task-definition-temp.json
sed -i "s|{{S3_BACKUPS_BUCKET}}|$S3_BACKUPS_BUCKET|g" ecs/task-definition-temp.json
sed -i "s|{{PAYMENT_API_URL}}|$PAYMENT_API_URL|g" ecs/task-definition-temp.json
sed -i "s|{{INVENTORY_SERVICE_URL}}|$INVENTORY_SERVICE_URL|g" ecs/task-definition-temp.json
sed -i "s|{{NOTIFICATION_SERVICE_URL}}|$NOTIFICATION_SERVICE_URL|g" ecs/task-definition-temp.json

echo "Task definition prepared"
echo ""

# Register task definition
echo "=== Registering Task Definition ==="
TASK_DEF_ARN=$(aws ecs register-task-definition \
    --cli-input-json file://ecs/task-definition-temp.json \
    --region "$AWS_REGION" \
    --query 'taskDefinition.taskDefinitionArn' \
    --output text)

if [ -z "$TASK_DEF_ARN" ]; then
    echo "Error: Failed to register task definition"
    rm -f ecs/task-definition-temp.json
    exit 1
fi

echo "Task definition registered: $TASK_DEF_ARN"
echo ""

# Clean up temporary file
rm -f ecs/task-definition-temp.json

# Prepare service definition
echo "=== Preparing Service Definition ==="
cp ecs/service-definition.json ecs/service-definition-temp.json

sed -i "s|{{CLUSTER_NAME}}|$CLUSTER_NAME|g" ecs/service-definition-temp.json
sed -i "s|{{SUBNET_1}}|$SUBNET_1|g" ecs/service-definition-temp.json
sed -i "s|{{SUBNET_2}}|$SUBNET_2|g" ecs/service-definition-temp.json
sed -i "s|{{SECURITY_GROUP}}|$SECURITY_GROUP|g" ecs/service-definition-temp.json

if [ "$USE_LB" = "false" ]; then
    # Remove loadBalancers section if no LB
    sed -i '/"loadBalancers":/,/],/d' ecs/service-definition-temp.json
    sed -i '/"healthCheckGracePeriodSeconds":/d' ecs/service-definition-temp.json
else
    sed -i "s|{{TARGET_GROUP_ARN}}|$TARGET_GROUP_ARN|g" ecs/service-definition-temp.json
fi

echo "Service definition prepared"
echo ""

# Check if service exists
echo "=== Checking if Service Exists ==="
SERVICE_NAME="resortslite-service"

EXISTING_SERVICE=$(aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query 'services[?status==`ACTIVE`].serviceName' \
    --output text)

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" = "None" ]; then
    echo "Service does not exist. Creating new service..."
    
    aws ecs create-service \
        --cli-input-json file://ecs/service-definition-temp.json \
        --region "$AWS_REGION"
    
    if [ $? -ne 0 ]; then
        echo "Error: Failed to create service"
        rm -f ecs/service-definition-temp.json
        exit 1
    fi
    
    echo "Service created successfully"
else
    echo "Service exists. Updating service..."
    
    aws ecs update-service \
        --cluster "$CLUSTER_NAME" \
        --service "$SERVICE_NAME" \
        --task-definition "$TASK_DEF_ARN" \
        --desired-count 2 \
        --region "$AWS_REGION"
    
    if [ $? -ne 0 ]; then
        echo "Error: Failed to update service"
        rm -f ecs/service-definition-temp.json
        exit 1
    fi
    
    echo "Service updated successfully"
fi

echo ""

# Clean up temporary file
rm -f ecs/service-definition-temp.json

# Wait for service to stabilize
echo "=== Waiting for Service to Stabilize ==="
echo "This may take several minutes..."
aws ecs wait services-stable \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION"

if [ $? -ne 0 ]; then
    echo "Warning: Service did not stabilize within the expected time"
    echo "Check the ECS console for more details"
else
    echo "Service is stable"
fi

echo ""

# Verify deployment
echo "=== Deployment Verification ==="
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
echo ""
echo "Cluster: $CLUSTER_NAME"
echo "Service: $SERVICE_NAME"
echo "Task Definition: $TASK_DEF_ARN"
echo "CloudWatch Logs: $LOG_GROUP"

if [ "$USE_LB" = "true" ]; then
    echo ""
    echo "Load Balancer DNS: $ALB_DNS"
    echo "Application URL: http://$ALB_DNS"
    echo ""
    echo "Note: It may take a few minutes for the load balancer to become fully operational"
fi

echo ""
echo "To view logs, run:"
echo "aws logs tail $LOG_GROUP --follow --region $AWS_REGION"
echo ""
echo "To check service status, run:"
echo "aws ecs describe-services --cluster $CLUSTER_NAME --services $SERVICE_NAME --region $AWS_REGION"
echo ""
