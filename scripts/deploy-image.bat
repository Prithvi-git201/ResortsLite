@echo off
setlocal enabledelayedexpansion

REM Deploy to AWS ECS Fargate Script for ResortsLite Application (Windows)
REM This script deploys the Docker image to AWS ECS Fargate

echo ==========================================
echo ResortsLite - Deploy to AWS ECS Fargate
echo ==========================================
echo.

REM Prompt for AWS region
set /p AWS_REGION="Enter AWS region (default: us-east-1): "
if "!AWS_REGION!"=="" set AWS_REGION=us-east-1

echo Using AWS region: !AWS_REGION!
echo.

REM Get AWS Account ID
echo === Getting AWS Account ID ===
for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%i

if "!ACCOUNT_ID!"=="" (
    echo Error: Failed to get AWS account ID. Please check your AWS credentials.
    exit /b 1
)

echo AWS Account ID: !ACCOUNT_ID!
echo.

REM Prompt for ECS cluster name
set /p CLUSTER_NAME="Enter ECS cluster name (default: resortslite-cluster): "
if "!CLUSTER_NAME!"=="" set CLUSTER_NAME=resortslite-cluster

echo Using cluster: !CLUSTER_NAME!
echo.

REM Check if cluster exists, create if it doesn't
echo === Checking ECS Cluster ===
aws ecs describe-clusters --clusters "!CLUSTER_NAME!" --region "!AWS_REGION!" >nul 2>&1

if !ERRORLEVEL! neq 0 (
    echo Cluster does not exist. Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name "!CLUSTER_NAME!" --region "!AWS_REGION!"
    if !ERRORLEVEL! neq 0 (
        echo Error: Failed to create cluster
        exit /b 1
    )
    echo ECS cluster created successfully
)
echo.

REM Prompt for VPC configuration
echo === Network Configuration ===
set /p VPC_ID="Enter VPC ID: "

if "!VPC_ID!"=="" (
    echo Error: VPC ID is required
    exit /b 1
)

REM Prompt for subnets (comma-separated)
set /p SUBNETS_INPUT="Enter subnet IDs (comma-separated, at least 2): "

if "!SUBNETS_INPUT!"=="" (
    echo Error: At least 2 subnet IDs are required for high availability
    exit /b 1
)

REM Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_INPUT!") do (
    set SUBNET_1=%%a
    set SUBNET_2=%%b
)

REM Trim spaces
set SUBNET_1=!SUBNET_1: =!
set SUBNET_2=!SUBNET_2: =!

if "!SUBNET_1!"=="" (
    echo Error: At least 2 subnet IDs are required
    exit /b 1
)

if "!SUBNET_2!"=="" (
    echo Error: At least 2 subnet IDs are required
    exit /b 1
)

echo Using subnets: !SUBNET_1!, !SUBNET_2!
echo.

REM Prompt for security group
set /p SECURITY_GROUP="Enter security group ID (must allow inbound traffic on port 8080): "

if "!SECURITY_GROUP!"=="" (
    echo Error: Security group ID is required
    exit /b 1
)

echo Using security group: !SECURITY_GROUP!
echo.

REM Prompt for Docker image URI
set /p IMAGE_URI="Enter Docker image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "

if "!IMAGE_URI!"=="" (
    echo Error: Docker image URI is required
    exit /b 1
)

echo Using image: !IMAGE_URI!
echo.

REM Prompt for load balancer
set /p NEED_LB="Do you need a load balancer for this service? (y/n): "

if /i "!NEED_LB!"=="y" (
    echo.
    echo === Creating Application Load Balancer ===
    
    set ALB_NAME=resortslite-alb
    echo Creating Application Load Balancer: !ALB_NAME!
    
    for /f "delims=" %%i in ('aws elbv2 create-load-balancer --name "!ALB_NAME!" --subnets "!SUBNET_1!" "!SUBNET_2!" --security-groups "!SECURITY_GROUP!" --scheme internet-facing --type application --ip-address-type ipv4 --region "!AWS_REGION!" --query "LoadBalancers[0].LoadBalancerArn" --output text') do set ALB_ARN=%%i
    
    if "!ALB_ARN!"=="" (
        echo Error: Failed to create Application Load Balancer
        exit /b 1
    )
    
    echo ALB created: !ALB_ARN!
    
    echo Waiting for ALB to become active...
    aws elbv2 wait load-balancer-available --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!"
    
    set TG_NAME=resortslite-tg
    echo Creating Target Group: !TG_NAME!
    
    for /f "delims=" %%i in ('aws elbv2 create-target-group --name "!TG_NAME!" --protocol HTTP --port 8080 --vpc-id "!VPC_ID!" --target-type ip --health-check-enabled --health-check-protocol HTTP --health-check-path "/actuator/health" --health-check-interval-seconds 30 --health-check-timeout-seconds 5 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region "!AWS_REGION!" --query "TargetGroups[0].TargetGroupArn" --output text') do set TARGET_GROUP_ARN=%%i
    
    if "!TARGET_GROUP_ARN!"=="" (
        echo Error: Failed to create Target Group
        exit /b 1
    )
    
    echo Target Group created: !TARGET_GROUP_ARN!
    
    echo Creating ALB Listener on port 80
    
    for /f "delims=" %%i in ('aws elbv2 create-listener --load-balancer-arn "!ALB_ARN!" --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn="!TARGET_GROUP_ARN!" --region "!AWS_REGION!" --query "Listeners[0].ListenerArn" --output text') do set LISTENER_ARN=%%i
    
    if "!LISTENER_ARN!"=="" (
        echo Error: Failed to create Listener
        exit /b 1
    )
    
    echo Listener created: !LISTENER_ARN!
    
    for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns "!ALB_ARN!" --region "!AWS_REGION!" --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%i
    
    echo ALB DNS Name: !ALB_DNS!
    echo.
    
    set USE_LB=true
) else (
    echo Skipping load balancer creation
    set TARGET_GROUP_ARN=
    set USE_LB=false
)

REM Prompt for application environment variables
echo === Application Configuration ===
echo Using existing configuration from application.properties
echo.

set /p REDIS_HOST="Enter Redis host (default: redis.example.com): "
if "!REDIS_HOST!"=="" set REDIS_HOST=redis.example.com

set /p REDIS_PORT="Enter Redis port (default: 6379): "
if "!REDIS_PORT!"=="" set REDIS_PORT=6379

set /p REDIS_PASSWORD="Enter Redis password (leave empty if none): "

set /p S3_REPORTS_BUCKET="Enter S3 reports bucket name (default: resort-reports-bucket): "
if "!S3_REPORTS_BUCKET!"=="" set S3_REPORTS_BUCKET=resort-reports-bucket

set /p S3_BACKUPS_BUCKET="Enter S3 backups bucket name (default: resort-backups-bucket): "
if "!S3_BACKUPS_BUCKET!"=="" set S3_BACKUPS_BUCKET=resort-backups-bucket

set /p PAYMENT_API_URL="Enter Payment API URL (default: https://payment-service:9090/charge): "
if "!PAYMENT_API_URL!"=="" set PAYMENT_API_URL=https://payment-service:9090/charge

set /p INVENTORY_SERVICE_URL="Enter Inventory Service URL (default: https://inventory-service:8081/rooms): "
if "!INVENTORY_SERVICE_URL!"=="" set INVENTORY_SERVICE_URL=https://inventory-service:8081/rooms

set /p NOTIFICATION_SERVICE_URL="Enter Notification Service URL (default: https://notify-service:7070/send): "
if "!NOTIFICATION_SERVICE_URL!"=="" set NOTIFICATION_SERVICE_URL=https://notify-service:7070/send

echo.

REM Create CloudWatch log group
echo === Creating CloudWatch Log Group ===
set LOG_GROUP=/ecs/resortslite

aws logs create-log-group --log-group-name "!LOG_GROUP!" --region "!AWS_REGION!" 2>nul
if !ERRORLEVEL! equ 0 (
    echo CloudWatch log group created: !LOG_GROUP!
) else (
    echo CloudWatch log group already exists: !LOG_GROUP!
)
echo.

REM Replace placeholders in task definition
echo === Preparing Task Definition ===
copy ecs\task-definition.json ecs\task-definition-temp.json >nul

powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{ACCOUNT_ID}}', '!ACCOUNT_ID!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{AWS_REGION}}', '!AWS_REGION!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{S3_REPORTS_BUCKET}}', '!S3_REPORTS_BUCKET!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{S3_BACKUPS_BUCKET}}', '!S3_BACKUPS_BUCKET!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{PAYMENT_API_URL}}', '!PAYMENT_API_URL!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{INVENTORY_SERVICE_URL}}', '!INVENTORY_SERVICE_URL!' | Set-Content ecs\task-definition-temp.json"
powershell -Command "(Get-Content ecs\task-definition-temp.json) -replace '{{NOTIFICATION_SERVICE_URL}}', '!NOTIFICATION_SERVICE_URL!' | Set-Content ecs\task-definition-temp.json"

echo Task definition prepared
echo.

REM Register task definition
echo === Registering Task Definition ===
for /f "delims=" %%i in ('aws ecs register-task-definition --cli-input-json file://ecs/task-definition-temp.json --region "!AWS_REGION!" --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%i

if "!TASK_DEF_ARN!"=="" (
    echo Error: Failed to register task definition
    del ecs\task-definition-temp.json
    exit /b 1
)

echo Task definition registered: !TASK_DEF_ARN!
echo.

REM Clean up temporary file
del ecs\task-definition-temp.json

REM Prepare service definition
echo === Preparing Service Definition ===
copy ecs\service-definition.json ecs\service-definition-temp.json >nul

powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{CLUSTER_NAME}}', '!CLUSTER_NAME!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SUBNET_1}}', '!SUBNET_1!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SUBNET_2}}', '!SUBNET_2!' | Set-Content ecs\service-definition-temp.json"
powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{SECURITY_GROUP}}', '!SECURITY_GROUP!' | Set-Content ecs\service-definition-temp.json"

if "!USE_LB!"=="false" (
    powershell -Command "$content = Get-Content ecs\service-definition-temp.json | Out-String | ConvertFrom-Json; $content.PSObject.Properties.Remove('loadBalancers'); $content.PSObject.Properties.Remove('healthCheckGracePeriodSeconds'); $content | ConvertTo-Json -Depth 10 | Set-Content ecs\service-definition-temp.json"
) else (
    powershell -Command "(Get-Content ecs\service-definition-temp.json) -replace '{{TARGET_GROUP_ARN}}', '!TARGET_GROUP_ARN!' | Set-Content ecs\service-definition-temp.json"
)

echo Service definition prepared
echo.

REM Check if service exists
echo === Checking if Service Exists ===
set SERVICE_NAME=resortslite-service

for /f "delims=" %%i in ('aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[?status==`ACTIVE`].serviceName" --output text') do set EXISTING_SERVICE=%%i

if "!EXISTING_SERVICE!"=="" (
    echo Service does not exist. Creating new service...
    
    aws ecs create-service --cli-input-json file://ecs/service-definition-temp.json --region "!AWS_REGION!"
    
    if !ERRORLEVEL! neq 0 (
        echo Error: Failed to create service
        del ecs\service-definition-temp.json
        exit /b 1
    )
    
    echo Service created successfully
) else (
    echo Service exists. Updating service...
    
    aws ecs update-service --cluster "!CLUSTER_NAME!" --service "!SERVICE_NAME!" --task-definition "!TASK_DEF_ARN!" --desired-count 2 --region "!AWS_REGION!"
    
    if !ERRORLEVEL! neq 0 (
        echo Error: Failed to update service
        del ecs\service-definition-temp.json
        exit /b 1
    )
    
    echo Service updated successfully
)

echo.

REM Clean up temporary file
del ecs\service-definition-temp.json

REM Wait for service to stabilize
echo === Waiting for Service to Stabilize ===
echo This may take several minutes...
aws ecs wait services-stable --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!"

if !ERRORLEVEL! neq 0 (
    echo Warning: Service did not stabilize within the expected time
    echo Check the ECS console for more details
) else (
    echo Service is stable
)

echo.

REM Verify deployment
echo === Deployment Verification ===
aws ecs describe-services --cluster "!CLUSTER_NAME!" --services "!SERVICE_NAME!" --region "!AWS_REGION!" --query "services[0].[serviceName,status,runningCount,desiredCount]" --output table

echo.
echo ==========================================
echo Deployment Completed Successfully!
echo ==========================================
echo.
echo Cluster: !CLUSTER_NAME!
echo Service: !SERVICE_NAME!
echo Task Definition: !TASK_DEF_ARN!
echo CloudWatch Logs: !LOG_GROUP!

if "!USE_LB!"=="true" (
    echo.
    echo Load Balancer DNS: !ALB_DNS!
    echo Application URL: http://!ALB_DNS!
    echo.
    echo Note: It may take a few minutes for the load balancer to become fully operational
)

echo.
echo To view logs, run:
echo aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo.
echo To check service status, run:
echo aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
echo.

endlocal
