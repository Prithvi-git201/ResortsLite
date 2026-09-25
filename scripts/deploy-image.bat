@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM deploy-image.bat — Deploy ResortsLite to AWS ECS Fargate (Windows)
REM Usage: scripts\deploy-image.bat
REM Prerequisites: AWS CLI configured, Python 3 installed
REM =============================================================================

set PROJECT_NAME=resortsLite
set SERVICE_NAME=resortsLite-service
set TASK_FAMILY=resortsLite-task
set LOG_GROUP=/ecs/resortsLite
set TASK_DEF_FILE=ecs\task-definition.json
set SERVICE_DEF_FILE=ecs\service-definition.json

echo ==============================================
echo   ResortsLite - ECS Fargate Deployment Script
echo ==============================================
echo.

REM Collect deployment parameters
set /p AWS_REGION="Enter AWS Region (e.g. us-east-1): "
set /p CLUSTER_INPUT="Enter ECS Cluster name [resortsLite-cluster]: "
if "!CLUSTER_INPUT!"=="" (
    set CLUSTER_NAME=resortsLite-cluster
) else (
    set CLUSTER_NAME=!CLUSTER_INPUT!
)

echo.
echo --- Network Configuration ---
set /p VPC_ID="Enter VPC ID (e.g. vpc-xxxxxxxx): "
set /p SUBNETS_INPUT="Enter Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): "
set /p SECURITY_GROUP="Enter Security Group ID (e.g. sg-xxxxxxxx): "

REM Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_INPUT!") do (
    set SUBNET_1=%%a
    set SUBNET_2=%%b
)
REM Trim spaces
for /f "tokens=*" %%a in ("!SUBNET_1!") do set SUBNET_1=%%a
for /f "tokens=*" %%a in ("!SUBNET_2!") do set SUBNET_2=%%a
if "!SUBNET_2!"=="" set SUBNET_2=!SUBNET_1!

echo.
set /p IMAGE_URI="Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest): "

echo.
echo --- Load Balancer ---
set /p NEED_ALB_INPUT="Do you need an Application Load Balancer? (y/n) [n]: "
if "!NEED_ALB_INPUT!"=="" set NEED_ALB_INPUT=n

REM Retrieve AWS Account ID
echo.
echo Retrieving AWS Account ID...
for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%a
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to retrieve AWS Account ID. Check AWS CLI credentials.
    exit /b 1
)
echo Account ID: !ACCOUNT_ID!

REM Ensure CloudWatch log group exists
echo.
echo Ensuring CloudWatch log group exists: !LOG_GROUP!
aws logs create-log-group --log-group-name !LOG_GROUP! --region !AWS_REGION! >nul 2>&1
echo Log group ready.

REM Ensure ECS cluster exists
echo.
echo Checking ECS cluster: !CLUSTER_NAME!
for /f "delims=" %%s in ('aws ecs describe-clusters --clusters !CLUSTER_NAME! --region !AWS_REGION! --query "clusters[0].status" --output text 2^>nul') do set CLUSTER_STATUS=%%s
if "!CLUSTER_STATUS!" neq "ACTIVE" (
    echo Creating ECS cluster: !CLUSTER_NAME!
    aws ecs create-cluster --cluster-name !CLUSTER_NAME! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
    echo Cluster created.
) else (
    echo Cluster already exists and is ACTIVE.
)

REM Prepare task definition using Python
echo.
echo Preparing task definition...
set TASK_DEF_TMP=%TEMP%\task-def-tmp.json
python -c "
import json, sys
with open(r'!TASK_DEF_FILE!') as f:
    content = f.read()
content = content.replace('{{ACCOUNT_ID}}', '!ACCOUNT_ID!')
content = content.replace('{{AWS_REGION}}', '!AWS_REGION!')
content = content.replace('{{IMAGE_URI}}', '!IMAGE_URI!')
content = content.replace('{{EFS_FILE_SYSTEM_ID}}', 'fs-placeholder')
with open(r'!TASK_DEF_TMP!', 'w') as f:
    f.write(content)
print('Task definition prepared.')
"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to prepare task definition.
    exit /b 1
)

REM Load Balancer setup (optional)
set TARGET_GROUP_ARN=
set ALB_DNS=
if /i "!NEED_ALB_INPUT!"=="y" (
    echo.
    echo Creating Application Load Balancer...
    set ALB_NAME=!PROJECT_NAME!-alb
    set TG_NAME=!PROJECT_NAME!-tg

    for /f "delims=" %%a in ('aws elbv2 create-load-balancer --name !ALB_NAME! --subnets !SUBNET_1! !SUBNET_2! --security-groups !SECURITY_GROUP! --scheme internet-facing --type application --region !AWS_REGION! --query "LoadBalancers[0].LoadBalancerArn" --output text') do set ALB_ARN=%%a
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB.
        exit /b 1
    )
    echo ALB created: !ALB_ARN!

    for /f "delims=" %%d in ('aws elbv2 describe-load-balancers --load-balancer-arns !ALB_ARN! --region !AWS_REGION! --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%d

    for /f "delims=" %%t in ('aws elbv2 create-target-group --name !TG_NAME! --protocol HTTP --port 8080 --vpc-id !VPC_ID! --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region !AWS_REGION! --query "TargetGroups[0].TargetGroupArn" --output text') do set TARGET_GROUP_ARN=%%t
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create Target Group.
        exit /b 1
    )
    echo Target Group created: !TARGET_GROUP_ARN!

    aws elbv2 create-listener --load-balancer-arn !ALB_ARN! --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region !AWS_REGION! >nul
    echo ALB listener created on port 80.
)

REM Prepare service definition
echo.
echo Preparing service definition...
set SERVICE_DEF_TMP=%TEMP%\service-def-tmp.json
python -c "
import json
with open(r'!SERVICE_DEF_FILE!') as f:
    content = f.read()
content = content.replace('{{CLUSTER_NAME}}', '!CLUSTER_NAME!')
content = content.replace('{{SUBNET_1}}', '!SUBNET_1!')
content = content.replace('{{SUBNET_2}}', '!SUBNET_2!')
content = content.replace('{{SECURITY_GROUP}}', '!SECURITY_GROUP!')
with open(r'!SERVICE_DEF_TMP!', 'w') as f:
    f.write(content)
print('Service definition prepared.')
"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to prepare service definition.
    exit /b 1
)

REM Add load balancer to service definition if needed
if /i "!NEED_ALB_INPUT!"=="y" (
    if "!TARGET_GROUP_ARN!" neq "" (
        python -c "
import json
with open(r'!SERVICE_DEF_TMP!') as f:
    svc = json.load(f)
svc['loadBalancers'] = [{'targetGroupArn': '!TARGET_GROUP_ARN!', 'containerName': '!PROJECT_NAME!', 'containerPort': 8080}]
svc['healthCheckGracePeriodSeconds'] = 300
with open(r'!SERVICE_DEF_TMP!', 'w') as f:
    json.dump(svc, f, indent=2)
print('Load balancer added to service definition.')
"
    )
)

REM Register task definition
echo.
echo Registering ECS task definition...
for /f "delims=" %%a in ('aws ecs register-task-definition --cli-input-json file://!TASK_DEF_TMP! --region !AWS_REGION! --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%a
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Task definition registered: !TASK_DEF_ARN!

REM Check if service exists
echo.
echo Checking if ECS service exists...
for /f "delims=" %%s in ('aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[?status==''ACTIVE''].serviceName" --output text 2^>nul') do set EXISTING_SERVICE=%%s

if "!EXISTING_SERVICE!"=="" (
    echo Creating new ECS service: !SERVICE_NAME!
    python -c "
import json
with open(r'!SERVICE_DEF_TMP!') as f:
    svc = json.load(f)
svc['cluster'] = '!CLUSTER_NAME!'
svc['taskDefinition'] = '!TASK_DEF_ARN!'
with open(r'!SERVICE_DEF_TMP!', 'w') as f:
    json.dump(svc, f, indent=2)
"
    aws ecs create-service --cli-input-json file://!SERVICE_DEF_TMP! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
    echo ECS service created.
) else (
    echo Updating existing ECS service: !SERVICE_NAME!
    aws ecs update-service --cluster !CLUSTER_NAME! --service !SERVICE_NAME! --task-definition !TASK_DEF_ARN! --region !AWS_REGION! >nul
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
    echo ECS service updated.
)

REM Wait for service stability
echo.
echo Waiting for service to reach stable state (this may take a few minutes)...
aws ecs wait services-stable --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not reach stable state within timeout. Check ECS console.
) else (
    echo Service is stable.
)

REM Verify deployment
echo.
echo ==============================================
echo   Deployment Verification
echo ==============================================
aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[0].{ServiceName:serviceName,Status:status,DesiredCount:desiredCount,RunningCount:runningCount}" --output table

echo.
echo CloudWatch Log Group: !LOG_GROUP!
echo   View logs: aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!

if /i "!NEED_ALB_INPUT!"=="y" (
    if "!ALB_DNS!" neq "" (
        echo.
        echo Application Load Balancer DNS: http://!ALB_DNS!
        echo Health Check URL: http://!ALB_DNS!/actuator/health
    )
)

echo.
echo ==============================================
echo   Deployment Complete!
echo   Cluster:  !CLUSTER_NAME!
echo   Service:  !SERVICE_NAME!
echo   Region:   !AWS_REGION!
echo ==============================================

REM Cleanup temp files
del /f /q !TASK_DEF_TMP! >nul 2>&1
del /f /q !SERVICE_DEF_TMP! >nul 2>&1

endlocal
