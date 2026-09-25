@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: deploy-image.bat — Deploy ResortsLite to AWS ECS Fargate (Windows)
:: Usage: scripts\deploy-image.bat
:: Run from the repository root directory.
:: =============================================================================

set SERVICE_NAME=resortslite-service
set TASK_FAMILY=resortslite-task
set LOG_GROUP=/ecs/resortslite
set TASK_DEF_FILE=ecs\task-definition.json
set SERVICE_DEF_FILE=ecs\service-definition.json

echo ==============================================
echo   ResortsLite - ECS Fargate Deployment
echo ==============================================
echo.

:: Collect deployment parameters
set /p AWS_REGION="Enter AWS Region [us-east-1]: "
if "!AWS_REGION!"=="" set AWS_REGION=us-east-1

set /p CLUSTER_NAME="Enter ECS Cluster name [resortslite-cluster]: "
if "!CLUSTER_NAME!"=="" set CLUSTER_NAME=resortslite-cluster

set /p IMAGE_URI="Enter ECR Image URI (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI is required.
    exit /b 1
)

set /p VPC_ID="Enter VPC ID: "
if "!VPC_ID!"=="" (
    echo ERROR: VPC ID is required.
    exit /b 1
)

set /p SUBNETS_RAW="Enter Subnet IDs (comma-separated, e.g. subnet-aaa,subnet-bbb): "
if "!SUBNETS_RAW!"=="" (
    echo ERROR: At least one subnet ID is required.
    exit /b 1
)

set /p SECURITY_GROUP="Enter Security Group ID: "
if "!SECURITY_GROUP!"=="" (
    echo ERROR: Security Group ID is required.
    exit /b 1
)

set /p EFS_FILE_SYSTEM_ID="Enter EFS File System ID (or press Enter to skip): "
if "!EFS_FILE_SYSTEM_ID!"=="" set EFS_FILE_SYSTEM_ID=fs-placeholder

:: Parse subnets
for /f "tokens=1,2 delims=," %%a in ("!SUBNETS_RAW!") do (
    set SUBNET_1=%%a
    set SUBNET_2=%%b
)
set SUBNET_1=!SUBNET_1: =!
set SUBNET_2=!SUBNET_2: =!
if "!SUBNET_2!"=="" set SUBNET_2=!SUBNET_1!

:: Retrieve AWS Account ID
echo.
echo Retrieving AWS Account ID...
for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%a
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to retrieve AWS Account ID. Check AWS CLI configuration.
    exit /b 1
)
echo Account ID : !ACCOUNT_ID!

:: Ensure CloudWatch log group exists
echo.
echo Ensuring CloudWatch log group '!LOG_GROUP!' exists...
aws logs create-log-group --log-group-name !LOG_GROUP! --region !AWS_REGION! >nul 2>&1
echo Log group ready.

:: Ensure ECS cluster exists
echo.
echo Checking ECS cluster '!CLUSTER_NAME!'...
for /f "delims=" %%s in ('aws ecs describe-clusters --clusters !CLUSTER_NAME! --region !AWS_REGION! --query "clusters[0].status" --output text 2^>nul') do set CLUSTER_STATUS=%%s
if not "!CLUSTER_STATUS!"=="ACTIVE" (
    echo Creating ECS cluster '!CLUSTER_NAME!'...
    aws ecs create-cluster --cluster-name !CLUSTER_NAME! --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS cluster.
        exit /b 1
    )
    echo Cluster created.
) else (
    echo Cluster '!CLUSTER_NAME!' is ACTIVE.
)

:: Load balancer (optional)
echo.
set /p NEED_LB="Do you need an Application Load Balancer for this service? (y/n) [n]: "
if "!NEED_LB!"=="" set NEED_LB=n

set TARGET_GROUP_ARN=
set ALB_DNS=

if /i "!NEED_LB!"=="y" (
    echo.
    echo Creating Application Load Balancer...

    for /f "delims=" %%a in ('aws elbv2 create-load-balancer --name resortslite-alb --subnets !SUBNET_1! !SUBNET_2! --security-groups !SECURITY_GROUP! --scheme internet-facing --type application --region !AWS_REGION! --query "LoadBalancers[0].LoadBalancerArn" --output text') do set ALB_ARN=%%a
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ALB.
        exit /b 1
    )
    echo ALB ARN: !ALB_ARN!

    for /f "delims=" %%d in ('aws elbv2 describe-load-balancers --load-balancer-arns !ALB_ARN! --region !AWS_REGION! --query "LoadBalancers[0].DNSName" --output text') do set ALB_DNS=%%d

    for /f "delims=" %%t in ('aws elbv2 create-target-group --name resortslite-tg --protocol HTTP --port 8080 --vpc-id !VPC_ID! --target-type ip --health-check-path "/actuator/health" --health-check-interval-seconds 30 --healthy-threshold-count 2 --unhealthy-threshold-count 3 --region !AWS_REGION! --query "TargetGroups[0].TargetGroupArn" --output text') do set TARGET_GROUP_ARN=%%t
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create Target Group.
        exit /b 1
    )
    echo Target Group ARN: !TARGET_GROUP_ARN!

    aws elbv2 create-listener --load-balancer-arn !ALB_ARN! --protocol HTTP --port 80 --default-actions "Type=forward,TargetGroupArn=!TARGET_GROUP_ARN!" --region !AWS_REGION! >nul
    echo ALB listener created on port 80.
)

:: Prepare task definition (replace placeholders using PowerShell)
echo.
echo Preparing task definition...
copy /Y !TASK_DEF_FILE! %TEMP%\task-definition-deploy.json >nul

powershell -NoProfile -Command ^
    "(Get-Content '%TEMP%\task-definition-deploy.json') -replace '{{ACCOUNT_ID}}','!ACCOUNT_ID!' -replace '{{AWS_REGION}}','!AWS_REGION!' -replace '{{IMAGE_URI}}','!IMAGE_URI!' -replace '{{EFS_FILE_SYSTEM_ID}}','!EFS_FILE_SYSTEM_ID!' | Set-Content '%TEMP%\task-definition-deploy.json'"

:: Register task definition
echo Registering task definition...
for /f "delims=" %%a in ('aws ecs register-task-definition --cli-input-json file://%TEMP%/task-definition-deploy.json --region !AWS_REGION! --query "taskDefinition.taskDefinitionArn" --output text') do set TASK_DEF_ARN=%%a
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to register task definition.
    exit /b 1
)
echo Task definition registered: !TASK_DEF_ARN!

:: Prepare service definition (replace placeholders using PowerShell)
echo.
echo Preparing service definition...
copy /Y !SERVICE_DEF_FILE! %TEMP%\service-definition-deploy.json >nul

powershell -NoProfile -Command ^
    "(Get-Content '%TEMP%\service-definition-deploy.json') -replace '{{CLUSTER_NAME}}','!CLUSTER_NAME!' -replace '{{SUBNET_1}}','!SUBNET_1!' -replace '{{SUBNET_2}}','!SUBNET_2!' -replace '{{SECURITY_GROUP}}','!SECURITY_GROUP!' | Set-Content '%TEMP%\service-definition-deploy.json'"

:: Inject task definition ARN and handle load balancer via PowerShell
if /i "!NEED_LB!"=="y" (
    powershell -NoProfile -Command ^
        "$svc = Get-Content '%TEMP%\service-definition-deploy.json' | ConvertFrom-Json; $svc.taskDefinition = '!TASK_DEF_ARN!'; $svc | Add-Member -NotePropertyName 'loadBalancers' -NotePropertyValue @(@{targetGroupArn='!TARGET_GROUP_ARN!';containerName='resortslite';containerPort=8080}) -Force; $svc | Add-Member -NotePropertyName 'healthCheckGracePeriodSeconds' -NotePropertyValue 300 -Force; $svc | ConvertTo-Json -Depth 10 | Set-Content '%TEMP%\service-definition-deploy.json'"
) else (
    powershell -NoProfile -Command ^
        "$svc = Get-Content '%TEMP%\service-definition-deploy.json' | ConvertFrom-Json; $svc.taskDefinition = '!TASK_DEF_ARN!'; $svc.PSObject.Properties.Remove('loadBalancers'); $svc.PSObject.Properties.Remove('healthCheckGracePeriodSeconds'); $svc | ConvertTo-Json -Depth 10 | Set-Content '%TEMP%\service-definition-deploy.json'"
)

:: Create or update ECS service
echo.
echo Checking if ECS service '!SERVICE_NAME!' exists...
for /f "delims=" %%s in ('aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[?status==''ACTIVE''].serviceName" --output text 2^>nul') do set EXISTING_SERVICE=%%s

if "!EXISTING_SERVICE!"=="" (
    echo Creating ECS service '!SERVICE_NAME!'...
    aws ecs create-service --cli-input-json file://%TEMP%/service-definition-deploy.json --region !AWS_REGION!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to create ECS service.
        exit /b 1
    )
    echo Service created.
) else (
    echo Updating existing ECS service '!SERVICE_NAME!'...
    aws ecs update-service --cluster !CLUSTER_NAME! --service !SERVICE_NAME! --task-definition !TASK_DEF_ARN! --region !AWS_REGION! >nul
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to update ECS service.
        exit /b 1
    )
    echo Service updated.
)

:: Wait for service stability
echo.
echo Waiting for service to become stable (this may take a few minutes)...
aws ecs wait services-stable --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION!
if !ERRORLEVEL! neq 0 (
    echo WARNING: Service did not stabilise within the expected time. Check ECS console.
)
echo Service is stable.

:: Verify deployment
echo.
echo Deployment verification:
aws ecs describe-services --cluster !CLUSTER_NAME! --services !SERVICE_NAME! --region !AWS_REGION! --query "services[0].{Status:status,Running:runningCount,Desired:desiredCount,Pending:pendingCount}" --output table

echo.
echo ==============================================
echo   Deployment Complete!
echo   Cluster      : !CLUSTER_NAME!
echo   Service      : !SERVICE_NAME!
echo   Task Def ARN : !TASK_DEF_ARN!
echo   CloudWatch   : !LOG_GROUP!
if /i "!NEED_LB!"=="y" echo   ALB DNS      : http://!ALB_DNS!
echo ==============================================
echo.
echo Troubleshooting tips:
echo   - View logs  : aws logs tail !LOG_GROUP! --follow --region !AWS_REGION!
echo   - List tasks : aws ecs list-tasks --cluster !CLUSTER_NAME! --region !AWS_REGION!

endlocal
