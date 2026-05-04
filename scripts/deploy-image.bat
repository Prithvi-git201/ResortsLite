@echo off
setlocal enabledelayedexpansion

REM Deploy to AWS ECS Fargate Script for ResortsLite Application (Windows)
REM This script deploys the Docker image to AWS ECS Fargate

echo ==========================================
echo ResortsLite - AWS ECS Fargate Deployment
echo ==========================================
echo.

REM Prompt for AWS Region
set /p AWS_REGION="Enter AWS Region (e.g., us-east-1): "
set AWS_DEFAULT_REGION=!AWS_REGION!

REM Get AWS Account ID
echo Retrieving AWS Account ID...
for /f "delims=" %%i in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%i
echo AWS Account ID: !ACCOUNT_ID!
echo.

REM Prompt for ECS Cluster Name
set /p CLUSTER_NAME="Enter ECS Cluster Name: "

REM Check if cluster exists, create if not
echo Checking if ECS cluster exists...
aws ecs describe-clusters --clusters !CLUSTER_NAME! --region !AWS_REGION! >nul 2>&1
if !ERRORLEVEL! neq 0 (
    echo Cluster does not exist. Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name !CLUSTER_NAME! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster
        exit /b 1
    )
    echo ECS cluster created successfully
)
echo.

REM Prompt for VPC and Network Configuration
echo === Network Configuration ===
set /p VPC_ID="Enter VPC ID: "
set /p SUBNET_1="Enter Subnet ID 1: "
set /p SUBNET_2="Enter Subnet ID 2: "
set /p SECURITY_GROUP="Enter Security Group ID: "
echo.

REM Prompt for Docker Image URI
set /p IMAGE_URI="Enter Docker Image URI (e.g., 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
echo.

REM Prompt for Redis Configuration
echo === Redis Configuration ===
set /p REDIS_HOST="Enter Redis Host (e.g., redis.example.com): "
set /p REDIS_PORT="Enter Redis Port (default: 6379): "
if "!REDIS_PORT!"=="" set REDIS_PORT=6379
set /p REDIS_PASSWORD="Enter Redis Password (leave empty if none): "
echo.

REM Prompt for S3 Configuration
echo === S3 Configuration ===
set /p S3_BUCKET_NAME="Enter S3 Bucket Name: "
echo.

REM Prompt for Notification Endpoint
echo === Notification Service Configuration ===
set /p APP_NOTIFICATION_ENDPOINT="Enter Notification Endpoint (default: http://notify.internal:7070/send): "
if "!APP_NOTIFICATION_ENDPOINT!"=="" set APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send
echo.

REM Ask about Load Balancer
set /p NEED_LB="Do you need a load balancer for this service? (y/n): "

if /i "!NEED_LB!"=="y" (
    echo.
    echo === Creating Application Load Balancer and Target Group ===
    
    REM Create Target Group
    echo Creating Target Group...
    set TARGET_GROUP_NAME=resortslite-tg-%RANDOM%
    
    for /f "delims=" %%i in ('aws elbv2 create-target-group --name !TARGET_GROUP_NAME! --protocol HTTP --port 8080 --vpc-id !VPC_ID! --target-type ip --health-check-enabled --health-check-protocol HTTP --health-check-path "/actuator/health" --health-check-interval-seconds 30 --health-check-timeout-seconds 5 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region !AWS_REGION! --query "TargetGroups[0].TargetGroupArn" --output text') do set TG_ARN=%%i
    
    echo Target Group created: !TG_ARN!
    
    REM Create Application Load Balancer
    echo Creating Application Load Balancer...
    set ALB_NAME=resortslite-alb-%RANDOM%
    
    for /f "delims=" %%i in ('aws elbv2 create-load-balancer --name !ALB_NAME! --subnets !SUBNET_1! !SUBNET_2! --security-groups !SECURITY_GROUP! --scheme internet-facing --type application --ip-address-type ipv4 --region !AWS_REGION! --query "LoadBalancers[0].LoadBalancerArn" --output text') do set ALB_ARN=%%i
    
    echo Application Load Balancer created: !ALB_ARN!
    
    REM Get ALB DNS Name
    for /f "delims=" %%i in ('aws elbv2 describe-load-balancers --load-balancer-arns !ALB_ARN! --region !AWS_REGION! --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%i
    
    echo ALB DNS Name: !ALB_DNS!
    
    REM Create Listener
    echo Creating ALB Listener...
    aws elbv2 create-listener --load-balancer-arn !ALB_ARN! --protocol HTTP --port 80 --default-actions Type=forward,TargetGroupArn=!TG_ARN! --region !AWS_REGION! >nul
    
    echo ALB Listener created successfully
    echo.
    
    set TARGET_GROUP_ARN=!TG_ARN!
) else (
    echo.
    echo Skipping load balancer creation. Service will be deployed without load balancer.
    echo.
    set TARGET_GROUP_ARN=
)

REM Create CloudWatch Log Group
echo Creating CloudWatch Log Group...
aws logs create-log-group --log-group-name "/ecs/resortslite" --region !AWS_REGION! 2>nul
if !ERRORLEVEL! neq 0 (
    echo Log group already exists
)
echo.

REM Replace placeholders in task definition
echo Preparing ECS Task Definition...
set TASK_DEF_FILE=ecs\task-definition.json
set TASK_DEF_TEMP=ecs\task-definition-temp.json

copy /Y !TASK_DEF_FILE! !TASK_DEF_TEMP! >nul

powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{IMAGE_URI}}', '!IMAGE_URI!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{ACCOUNT_ID}}', '!ACCOUNT_ID!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{AWS_REGION}}', '!AWS_REGION!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{REDIS_HOST}}', '!REDIS_HOST!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{REDIS_PORT}}', '!REDIS_PORT!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{REDIS_PASSWORD}}', '!REDIS_PASSWORD!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{S3_BUCKET_NAME}}', '!S3_BUCKET_NAME!' | Set-Content !TASK_DEF_TEMP!"
powershell -Command "(Get-Content !TASK_DEF_TEMP!) -replace '{{APP_NOTIFICATION_ENDPOINT}}', '!APP_NOTIFICATION_ENDPOINT!' | Set-Content !TASK_DEF_TEMP!"

REM Register Task Definition
echo Registering ECS Task Definition...
for /f "delims=" %%i in ('aws ecs register-task-definition --cli-input-json file://!TASK_DEF_TEMP! --region !AWS_REGION! --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%i

if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition
    del /F !TASK_DEF_TEMP! 2>nul
    exit /b 1
)

echo Task Definition registered: !TASK_DEF_ARN!
echo.

REM Clean up temp file
del /F !TASK_DEF_TEMP! 2>nul

REM Prepare service definition
echo Preparing ECS Service Definition...
set SERVICE_DEF_FILE=ecs\service-definition.json
set SERVICE_DEF_TEMP=ecs\service-definition-temp.json

copy /Y !SERVICE_DEF_FILE! !SERVICE_DEF_TEMP! >nul

powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{CLUSTER_NAME}}', '!CLUSTER_NAME!' | Set-Content !SERVICE_DEF_TEMP!"
powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{SUBNET_1}}', '!SUBNET_1!' | Set-Content !SERVICE_DEF_TEMP!"
powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{SUBNET_2}}', '!SUBNET_2!' | Set-Content !SERVICE_DEF_TEMP!"
powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{SECURITY_GROUP}}', '!SECURITY_GROUP!' | Set-Content !SERVICE_DEF_TEMP!"

if "!TARGET_GROUP_ARN!"=="" (
    REM Remove loadBalancers section if no load balancer
    powershell -Command "$content = Get-Content !SERVICE_DEF_TEMP! -Raw; $content = $content -replace '(?s)\"loadBalancers\":\s*\[.*?\],\s*', ''; $content = $content -replace '\"healthCheckGracePeriodSeconds\":\s*\d+,\s*', ''; $content | Set-Content !SERVICE_DEF_TEMP!"
) else (
    powershell -Command "(Get-Content !SERVICE_DEF_TEMP!) -replace '{{TARGET_GROUP_ARN}}', '!TARGET_GROUP_ARN!' | Set-Content !SERVICE_DEF_TEMP!"
)

REM Check if service exists
set SERVICE_NAME=resortslite-service
echo Checking if ECS service exists...
for /f "delims=" %%i in ('aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[?status==`ACTIVE`].serviceName" --output text 2^>nul') do set EXISTING_SERVICE=%%i

if "!EXISTING_SERVICE!"=="" (
    REM Create new service
    echo Creating new ECS service...
    aws ecs create-service --cli-input-json file://!SERVICE_DEF_TEMP! --region !AWS_REGION! >nul
    
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service
        del /F !SERVICE_DEF_TEMP! 2>nul
        exit /b 1
    )
    
    echo ECS service created successfully
) else (
    REM Update existing service
    echo Updating existing ECS service...
    aws ecs update-service --cluster !CLUSTER_NAME! --service !SERVICE_NAME! --task-definition !TASK_DEF_ARN! --desired-count 2 --region !AWS_REGION! >nul
    
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service
        del /F !SERVICE_DEF_TEMP! 2>nul
        exit /b 1
    )
    
    echo ECS service updated successfully
)

echo.

REM Clean up temp file
del /F !SERVICE_DEF_TEMP! 2>nul

REM Wait for service to stabilize
echo Waiting for service to become stable (this may take a few minutes)...
aws ecs wait services-stable --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!

if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not stabilize within expected time
)

echo.
echo ==========================================
echo DEPLOYMENT SUCCESSFUL!
echo ==========================================
echo.

REM Display service information
echo Service Details:
aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[0].[serviceName,status,runningCount,desiredCount]" --output table

echo.
echo CloudWatch Logs:
echo   Log Group: /ecs/resortslite
echo   Region: !AWS_REGION!
echo   View logs: https://console.aws.amazon.com/cloudwatch/home?region=!AWS_REGION!#logsV2:log-groups/log-group/$252Fecs$252Fresortslite
echo.

if not "!TARGET_GROUP_ARN!"=="" (
    echo Application Load Balancer:
    echo   DNS Name: !ALB_DNS!
    echo   Access your application at: http://!ALB_DNS!
    echo.
)

echo Deployment completed successfully!
echo ==========================================

endlocal
