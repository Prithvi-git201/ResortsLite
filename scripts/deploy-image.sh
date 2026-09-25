#!/bin/bash
# =============================================================================
# deploy-image.sh — Deploy ResortsLite to AWS ECS Fargate
# Usage: ./scripts/deploy-image.sh
# Prerequisites: AWS CLI configured, jq installed
# =============================================================================

set -e
set -o pipefail

PROJECT_NAME="resortsLite"
SERVICE_NAME="${PROJECT_NAME}-service"
TASK_FAMILY="${PROJECT_NAME}-task"
LOG_GROUP="/ecs/${PROJECT_NAME}"
TASK_DEF_FILE="ecs/task-definition.json"
SERVICE_DEF_FILE="ecs/service-definition.json"

echo "=============================================="
echo "  ResortsLite — ECS Fargate Deployment Script"
echo "=============================================="
echo ""

# ------------------------------------------------------------------------------
# Collect deployment parameters
# ------------------------------------------------------------------------------
read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
read -rp "Enter ECS Cluster name [resortsLite-cluster]: " CLUSTER_INPUT
CLUSTER_NAME="${CLUSTER_INPUT:-resortsLite-cluster}"

echo ""
echo "--- Network Configuration ---"
read -rp "Enter VPC ID (e.g. vpc-xxxxxxxx): " VPC_ID
read -rp "Enter Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): " SUBNETS_INPUT
read -rp "Enter Security Group ID (e.g. sg-xxxxxxxx): " SECURITY_GROUP

# Parse subnets
SUBNET_1=$(echo "$SUBNETS_INPUT" | cut -d',' -f1 | tr -d ' ')
SUBNET_2=$(echo "$SUBNETS_INPUT" | cut -d',' -f2 | tr -d ' ')
if [ -z "$SUBNET_2" ]; then
  SUBNET_2="$SUBNET_1"
fi

echo ""
read -rp "Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): " IMAGE_URI

echo ""
echo "--- Load Balancer ---"
read -rp "Do you need an Application Load Balancer for this service? (y/n) [n]: " NEED_ALB_INPUT
NEED_ALB="${NEED_ALB_INPUT:-n}"

# ------------------------------------------------------------------------------
# Retrieve AWS Account ID
# ------------------------------------------------------------------------------
echo ""
echo "Retrieving AWS Account ID..."
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
echo "Account ID: $ACCOUNT_ID"

# ------------------------------------------------------------------------------
# Ensure CloudWatch log group exists
# ------------------------------------------------------------------------------
echo ""
echo "Ensuring CloudWatch log group exists: $LOG_GROUP"
aws logs create-log-group --log-group-name "$LOG_GROUP" --region "$AWS_REGION" 2>/dev/null || true
echo "Log group ready."

# ------------------------------------------------------------------------------
# Ensure ECS cluster exists
# ------------------------------------------------------------------------------
echo ""
echo "Checking ECS cluster: $CLUSTER_NAME"
CLUSTER_STATUS=$(aws ecs describe-clusters --clusters "$CLUSTER_NAME" --region "$AWS_REGION" \
  --query "clusters[0].status" --output text 2>/dev/null || echo "MISSING")

if [ "$CLUSTER_STATUS" != "ACTIVE" ]; then
  echo "Creating ECS cluster: $CLUSTER_NAME"
  aws ecs create-cluster --cluster-name "$CLUSTER_NAME" --region "$AWS_REGION"
  echo "Cluster created."
else
  echo "Cluster already exists and is ACTIVE."
fi

# ------------------------------------------------------------------------------
# Prepare task definition — replace placeholders
# ------------------------------------------------------------------------------
echo ""
echo "Preparing task definition..."
TASK_DEF_TMP=$(mktemp /tmp/task-def-XXXXXX.json)
sed \
  -e "s|{{ACCOUNT_ID}}|${ACCOUNT_ID}|g" \
  -e "s|{{AWS_REGION}}|${AWS_REGION}|g" \
  -e "s|{{IMAGE_URI}}|${IMAGE_URI}|g" \
  -e "s|{{EFS_FILE_SYSTEM_ID}}|fs-placeholder|g" \
  "$TASK_DEF_FILE" > "$TASK_DEF_TMP"

# ------------------------------------------------------------------------------
# Load Balancer setup (optional)
# ------------------------------------------------------------------------------
TARGET_GROUP_ARN=""
ALB_DNS=""
if [[ "$NEED_ALB" =~ ^[Yy]$ ]]; then
  echo ""
  echo "Creating Application Load Balancer..."

  ALB_NAME="${PROJECT_NAME}-alb"
  TG_NAME="${PROJECT_NAME}-tg"

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
  echo "ALB created: $ALB_ARN"

  ALB_DNS=$(aws elbv2 describe-load-balancers \
    --load-balancer-arns "$ALB_ARN" \
    --region "$AWS_REGION" \
    --query "LoadBalancers[0].DNSName" \
    --output text)

  # Create Target Group (target-type ip required for Fargate awsvpc)
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
  echo "Target Group created: $TARGET_GROUP_ARN"

  # Create listener
  aws elbv2 create-listener \
    --load-balancer-arn "$ALB_ARN" \
    --protocol HTTP \
    --port 80 \
    --default-actions "Type=forward,TargetGroupArn=${TARGET_GROUP_ARN}" \
    --region "$AWS_REGION" > /dev/null
  echo "ALB listener created on port 80."
fi

# ------------------------------------------------------------------------------
# Prepare service definition — replace placeholders
# ------------------------------------------------------------------------------
echo ""
echo "Preparing service definition..."
SERVICE_DEF_TMP=$(mktemp /tmp/service-def-XXXXXX.json)
sed \
  -e "s|{{CLUSTER_NAME}}|${CLUSTER_NAME}|g" \
  -e "s|{{SUBNET_1}}|${SUBNET_1}|g" \
  -e "s|{{SUBNET_2}}|${SUBNET_2}|g" \
  -e "s|{{SECURITY_GROUP}}|${SECURITY_GROUP}|g" \
  "$SERVICE_DEF_FILE" > "$SERVICE_DEF_TMP"

# Add load balancer configuration if ALB was created
if [[ "$NEED_ALB" =~ ^[Yy]$ ]] && [ -n "$TARGET_GROUP_ARN" ]; then
  # Use Python to inject loadBalancers into service definition JSON
  python3 - <<PYEOF
import json, sys

with open("$SERVICE_DEF_TMP") as f:
    svc = json.load(f)

svc["loadBalancers"] = [{
    "targetGroupArn": "$TARGET_GROUP_ARN",
    "containerName": "$PROJECT_NAME",
    "containerPort": 8080
}]
svc["healthCheckGracePeriodSeconds"] = 300

with open("$SERVICE_DEF_TMP", "w") as f:
    json.dump(svc, f, indent=2)
PYEOF
  echo "Load balancer configuration added to service definition."
fi

# ------------------------------------------------------------------------------
# Register task definition
# ------------------------------------------------------------------------------
echo ""
echo "Registering ECS task definition..."
TASK_DEF_ARN=$(aws ecs register-task-definition \
  --cli-input-json "file://${TASK_DEF_TMP}" \
  --region "$AWS_REGION" \
  --query "taskDefinition.taskDefinitionArn" \
  --output text)
echo "Task definition registered: $TASK_DEF_ARN"

# ------------------------------------------------------------------------------
# Create or update ECS service
# ------------------------------------------------------------------------------
echo ""
echo "Checking if ECS service exists..."
EXISTING_SERVICE=$(aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[?status=='ACTIVE'].serviceName" \
  --output text 2>/dev/null || echo "")

if [ -z "$EXISTING_SERVICE" ] || [ "$EXISTING_SERVICE" = "None" ]; then
  echo "Creating new ECS service: $SERVICE_NAME"
  # Inject cluster and task definition ARN into service definition
  python3 - <<PYEOF
import json

with open("$SERVICE_DEF_TMP") as f:
    svc = json.load(f)

svc["cluster"] = "$CLUSTER_NAME"
svc["taskDefinition"] = "$TASK_DEF_ARN"

with open("$SERVICE_DEF_TMP", "w") as f:
    json.dump(svc, f, indent=2)
PYEOF
  aws ecs create-service \
    --cli-input-json "file://${SERVICE_DEF_TMP}" \
    --region "$AWS_REGION"
  echo "ECS service created."
else
  echo "Updating existing ECS service: $SERVICE_NAME"
  aws ecs update-service \
    --cluster "$CLUSTER_NAME" \
    --service "$SERVICE_NAME" \
    --task-definition "$TASK_DEF_ARN" \
    --region "$AWS_REGION" > /dev/null
  echo "ECS service updated."
fi

# ------------------------------------------------------------------------------
# Wait for service stability
# ------------------------------------------------------------------------------
echo ""
echo "Waiting for service to reach stable state (this may take a few minutes)..."
aws ecs wait services-stable \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION"
echo "Service is stable."

# ------------------------------------------------------------------------------
# Verify deployment
# ------------------------------------------------------------------------------
echo ""
echo "=============================================="
echo "  Deployment Verification"
echo "=============================================="
aws ecs describe-services \
  --cluster "$CLUSTER_NAME" \
  --services "$SERVICE_NAME" \
  --region "$AWS_REGION" \
  --query "services[0].{ServiceName:serviceName,Status:status,DesiredCount:desiredCount,RunningCount:runningCount,PendingCount:pendingCount}" \
  --output table

echo ""
echo "CloudWatch Log Group: $LOG_GROUP"
echo "  View logs: aws logs tail $LOG_GROUP --follow --region $AWS_REGION"

if [[ "$NEED_ALB" =~ ^[Yy]$ ]] && [ -n "$ALB_DNS" ]; then
  echo ""
  echo "Application Load Balancer DNS: http://$ALB_DNS"
  echo "Health Check URL: http://$ALB_DNS/actuator/health"
fi

echo ""
echo "=============================================="
echo "  Deployment Complete!"
echo "  Cluster:  $CLUSTER_NAME"
echo "  Service:  $SERVICE_NAME"
echo "  Region:   $AWS_REGION"
echo "=============================================="

# Cleanup temp files
rm -f "$TASK_DEF_TMP" "$SERVICE_DEF_TMP"
