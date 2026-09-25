#!/usr/bin/env bash
# =============================================================================
# deploy-image.sh — Deploy ResortsLite to AWS ECS Fargate
# Usage: ./scripts/deploy-image.sh
# Run from the repository root directory.
# =============================================================================
set -e
set -o pipefail

SERVICE_NAME="resortslite-service"
TASK_FAMILY="resortslite-task"
LOG_GROUP="/ecs/resortslite"
TASK_DEF_FILE="ecs/task-definition.json"
SERVICE_DEF_FILE="ecs/service-definition.json"

echo "=============================================="
echo "  ResortsLite — ECS Fargate Deployment"
echo "=============================================="
echo ""

# -----------------------------------------------------------------------------
# Collect deployment parameters
# -----------------------------------------------------------------------------
read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
AWS_REGION="${AWS_REGION:-us-east-1}"

read -rp "Enter ECS Cluster name [resortslite-cluster]: " CLUSTER_NAME
CLUSTER_NAME="${CLUSTER_NAME:-resortslite-cluster}"

read -rp "Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
    echo "ERROR: Image URI is required."
    exit 1
fi

read -rp "Enter VPC ID: " VPC_ID
if [ -z "$VPC_ID" ]; then
    echo "ERROR: VPC ID is required."
    exit 1
fi

read -rp "Enter Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): " SUBNETS_RAW
if [ -z "$SUBNETS_RAW" ]; then
    echo "ERROR: At least one subnet ID is required."
    exit 1
fi

read -rp "Enter Security Group ID: " SECURITY_GROUP
if [ -z "$SECURITY_GROUP" ]; then
    echo "ERROR: Security Group ID is required."
    exit 1
fi

read -rp "Enter EFS File System ID (for report/backup volumes, or press Enter to skip): " EFS_FILE_SYSTEM_ID
EFS_FILE_SYSTEM_ID="${EFS_FILE_SYSTEM_ID:-fs-placeholder}"

# Parse subnets into JSON array
SUBNET_1=$(echo "$SUBNETS_RAW" | cut -d',' -f1 | tr -d ' ')
SUBNET_2=$(echo "$SUBNETS_RAW" | cut -d',' -f2 | tr -d ' ')
if [ -z "$SUBNET_2" ]; then
    SUBNET_2="$SUBNET_1"
fi

# Retrieve AWS Account ID
echo ""
echo "Retrieving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID : $ACCOUNT_ID"

# -----------------------------------------------------------------------------
# Ensure CloudWatch log group exists
# -----------------------------------------------------------------------------
echo ""
echo "Ensuring CloudWatch log group '$LOG_GROUP' exists..."
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || true
echo "Log group ready."

# -----------------------------------------------------------------------------
# Ensure ECS cluster exists
# -----------------------------------------------------------------------------
echo ""
echo "Checking ECS cluster '$CLUSTER_NAME'..."
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" \
    --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")

if [ "$CLUSTER_STATUS" != "ACTIVE" ]; then
    echo "Creating ECS cluster '$CLUSTER_NAME'..."
    aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
    echo "Cluster created."
else
    echo "Cluster '$CLUSTER_NAME' is ACTIVE."
fi

# -----------------------------------------------------------------------------
# Load balancer (optional)
# -----------------------------------------------------------------------------
echo ""
read -rp "Do you need an Application Load Balancer for this service? (y/n) [n]: " NEED_LB
NEED_LB="${NEED_LB:-n}"

TARGET_GROUP_ARN=""
ALB_DNS=""

if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
    echo ""
    echo "Creating Application Load Balancer..."

    ALB_NAME="resortslite-alb"
    TG_NAME="resortslite-tg"

    # Create ALB
    ALB_ARN=$(aws elbv2 create-load-balancer \
        --name "$ALB_NAME" \
        --subnets "$SUBNET_1" "$SUBNET_2" \
        --security-groups "$SECURITY_GROUP" \
        --scheme internet-facing \
        --type application \
        --region "$AWS_REGION" \
        --query "LoadBalancers[0].LoadBalancerArn" \
        --output text)
    echo "ALB ARN: $ALB_ARN"

    ALB_DNS=$(aws elbv2 describe-load-balancers \
        --load-balancer-arns "$ALB_ARN" \
        --region "$AWS_REGION" \
        --query "LoadBalancers[0].DNSName" \
        --output text)

    # Create Target Group (target-type=ip required for Fargate awsvpc mode)
    TARGET_GROUP_ARN=$(aws elbv2 create-target-group \
        --name "$TG_NAME" \
        --protocol HTTP \
        --port 8080 \
        --vpc-id "$VPC_ID" \
        --target-type ip \
        --health-check-path "/actuator/health" \
        --health-check-interval-seconds 30 \
        --healthy-threshold-count 2 \
        --unhealthy-threshold-count 3 \
        --region "$AWS_REGION" \
        --query "TargetGroups[0].TargetGroupArn" \
        --output text)
    echo "Target Group ARN: $TARGET_GROUP_ARN"

    # Create listener
    aws elbv2 create-listener \
        --load-balancer-arn "$ALB_ARN" \
        --protocol HTTP \
        --port 80 \
        --default-actions "Type=forward,TargetGroupArn=$TARGET_GROUP_ARN" \
        --region "$AWS_REGION" >/dev/null
    echo "ALB listener created on port 80."
fi

# -----------------------------------------------------------------------------
# Prepare task definition JSON (replace placeholders)
# -----------------------------------------------------------------------------
echo ""
echo "Preparing task definition..."
cp "$TASK_DEF_FILE" /tmp/task-definition-deploy.json

sed -i "s|{{ACCOUNT_ID}}|${ACCOUNT_ID}|g"           /tmp/task-definition-deploy.json
sed -i "s|{{AWS_REGION}}|${AWS_REGION}|g"           /tmp/task-definition-deploy.json
sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"             /tmp/task-definition-deploy.json
sed -i "s|{{EFS_FILE_SYSTEM_ID}}|${EFS_FILE_SYSTEM_ID}|g" /tmp/task-definition-deploy.json

# -----------------------------------------------------------------------------
# Register task definition
# -----------------------------------------------------------------------------
echo "Registering task definition..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
    --cli-input-json file:///tmp/task-definition-deploy.json \
    --region "$AWS_REGION" \
    --query "taskDefinition.taskDefinitionArn" \
    --output text)
echo "Task definition registered: $TASK_DEF_ARN"

# -----------------------------------------------------------------------------
# Prepare service definition JSON (replace placeholders)
# -----------------------------------------------------------------------------
echo ""
echo "Preparing service definition..."
cp "$SERVICE_DEF_FILE" /tmp/service-definition-deploy.json

sed -i "s|{{CLUSTER_NAME}}|${CLUSTER_NAME}|g"       /tmp/service-definition-deploy.json
sed -i "s|{{SUBNET_1}}|${SUBNET_1}|g"               /tmp/service-definition-deploy.json
sed -i "s|{{SUBNET_2}}|${SUBNET_2}|g"               /tmp/service-definition-deploy.json
sed -i "s|{{SECURITY_GROUP}}|${SECURITY_GROUP}|g"   /tmp/service-definition-deploy.json

# Inject task definition ARN
python3 -c "
import json, sys
with open('/tmp/service-definition-deploy.json') as f:
    svc = json.load(f)
svc['taskDefinition'] = sys.argv[1]
with open('/tmp/service-definition-deploy.json', 'w') as f:
    json.dump(svc, f, indent=2)
" "$TASK_DEF_ARN"

# Handle load balancer section
if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
    python3 -c "
import json, sys
with open('/tmp/service-definition-deploy.json') as f:
    svc = json.load(f)
svc['loadBalancers'] = [{
    'targetGroupArn': sys.argv[1],
    'containerName': 'resortslite',
    'containerPort': 8080
}]
svc['healthCheckGracePeriodSeconds'] = 300
with open('/tmp/service-definition-deploy.json', 'w') as f:
    json.dump(svc, f, indent=2)
" "$TARGET_GROUP_ARN"
else
    # Remove loadBalancers key if present
    python3 -c "
import json
with open('/tmp/service-definition-deploy.json') as f:
    svc = json.load(f)
svc.pop('loadBalancers', None)
svc.pop('healthCheckGracePeriodSeconds', None)
with open('/tmp/service-definition-deploy.json', 'w') as f:
    json.dump(svc, f, indent=2)
"
fi

# -----------------------------------------------------------------------------
# Create or update ECS service
# -----------------------------------------------------------------------------
echo ""
echo "Checking if ECS service '$SERVICE_NAME' exists..."
EXISTING_SERVICE=$(aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query "services[?status=='ACTIVE'].serviceName" \
    --output text 2>/dev/null || echo "")

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" = "None" ]; then
    echo "Creating ECS service '$SERVICE_NAME'..."
    aws ecs create-service \
        --cli-input-json file:///tmp/service-definition-deploy.json \
        --region "$AWS_REGION"
    echo "Service created."
else
    echo "Updating existing ECS service '$SERVICE_NAME'..."
    aws ecs update-service \
        --cluster "$CLUSTER_NAME" \
        --service "$SERVICE_NAME" \
        --task-definition "$TASK_DEF_ARN" \
        --region "$AWS_REGION" >/dev/null
    echo "Service updated."
fi

# -----------------------------------------------------------------------------
# Wait for service stability
# -----------------------------------------------------------------------------
echo ""
echo "Waiting for service to become stable (this may take a few minutes)..."
aws ecs wait services-stable \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION"
echo "Service is stable."

# -----------------------------------------------------------------------------
# Verify deployment
# -----------------------------------------------------------------------------
echo ""
echo "Deployment verification:"
aws ecs describe-services \
    --cluster "$CLUSTER_NAME" \
    --services "$SERVICE_NAME" \
    --region "$AWS_REGION" \
    --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,Pending:pendingCount}" \
    --output table

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Cluster      : $CLUSTER_NAME"
echo "  Service      : $SERVICE_NAME"
echo "  Task Def ARN : $TASK_DEF_ARN"
echo "  CloudWatch   : $LOG_GROUP"
if [[ "$NEED_LB" =~ ^[Yy]$ ]]; then
    echo "  ALB DNS      : http://$ALB_DNS"
fi
echo "=============================================="
echo ""
echo "Troubleshooting tips:"
echo "  - View logs  : aws logs tail $LOG_GROUP --follow --region $AWS_REGION"
echo "  - List tasks : aws ecs list-tasks --cluster $CLUSTER_NAME --region $AWS_REGION"
echo "  - Task detail: aws ecs describe-tasks --cluster $CLUSTER_NAME --tasks <TASK_ARN> --region $AWS_REGION"
