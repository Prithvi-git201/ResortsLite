@echo off
setlocal enabledelayedexpansion

REM =============================================================================
REM build-push.bat — Build and push ResortsLite Docker image (Windows)
REM Supports: AWS ECR and Docker Hub
REM Usage: scripts\build-push.bat
REM Run from repository root directory
REM =============================================================================

set PROJECT_NAME=resortsLite
set DOCKERFILE_PATH=Dockerfile
set BUILD_CONTEXT=.

echo ==============================================
echo   ResortsLite - Docker Build ^& Push Script
echo ==============================================
echo.

REM Sanitize image name using PowerShell
for /f "delims=" %%i in ('powershell -NoProfile -Command "$n = 'resortsLite'.ToLower() -replace '[^a-z0-9]','-'; $n = $n.Trim('-'); Write-Output $n"') do set IMAGE_NAME=%%i
echo Image name (sanitized): !IMAGE_NAME!
echo.

REM Prompt for image tag
set /p IMAGE_TAG_INPUT="Enter image tag [latest]: "
if "!IMAGE_TAG_INPUT!"=="" (
    set IMAGE_TAG=latest
) else (
    for /f "delims=" %%t in ('powershell -NoProfile -Command "$t = '!IMAGE_TAG_INPUT!'.ToLower() -replace '[^a-z0-9._-]','-'; $t = $t.Trim('-'); if ($t -eq '') { 'latest' } else { $t }"') do set IMAGE_TAG=%%t
)
echo Image tag: !IMAGE_TAG!
echo.

REM Registry selection
echo Select container registry:
echo   1. AWS ECR (Elastic Container Registry)
echo   2. Docker Hub
echo.
set /p REGISTRY_CHOICE="Enter choice [1 or 2]: "

if "!REGISTRY_CHOICE!"=="1" (
    echo.
    echo --- AWS ECR Configuration ---
    set /p AWS_REGION="Enter AWS Region (e.g. us-east-1): "
    set /p ECR_REPO_INPUT="Enter ECR repository name [!IMAGE_NAME!]: "
    if "!ECR_REPO_INPUT!"=="" (
        set ECR_REPO=!IMAGE_NAME!
    ) else (
        set ECR_REPO=!ECR_REPO_INPUT!
    )

    echo.
    echo Retrieving AWS Account ID...
    for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%a
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to retrieve AWS Account ID. Check AWS CLI credentials.
        exit /b 1
    )
    echo Account ID: !ACCOUNT_ID!

    set REGISTRY_URL=!ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com
    set FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!

    echo.
    echo Authenticating with ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed. Check AWS credentials and region.
        exit /b 1
    )
    echo ECR login successful.

    echo.
    echo Checking if ECR repository exists...
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository: !ECR_REPO!
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ERROR: Failed to create ECR repository.
            exit /b 1
        )
    )
    echo ECR repository ready: !ECR_REPO!

) else if "!REGISTRY_CHOICE!"=="2" (
    echo.
    echo --- Docker Hub Configuration ---
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    set /p DOCKERHUB_REPO_INPUT="Enter Docker Hub repository name [!DOCKER_USERNAME!/!IMAGE_NAME!]: "
    if "!DOCKERHUB_REPO_INPUT!"=="" (
        set DOCKERHUB_REPO=!DOCKER_USERNAME!/!IMAGE_NAME!
    ) else (
        set DOCKERHUB_REPO=!DOCKERHUB_REPO_INPUT!
    )

    set FULL_IMAGE_NAME=!DOCKERHUB_REPO!:!IMAGE_TAG!

    echo.
    echo Authenticating with Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed. Check credentials.
        exit /b 1
    )
    echo Docker Hub login successful.

) else (
    echo ERROR: Invalid choice. Please enter 1 or 2.
    exit /b 1
)

echo.
echo ==============================================
echo   Building Docker image...
echo   Image: !FULL_IMAGE_NAME!
echo   Dockerfile: !DOCKERFILE_PATH!
echo   Context: !BUILD_CONTEXT!
echo ==============================================
docker build -f !DOCKERFILE_PATH! -t !FULL_IMAGE_NAME! !BUILD_CONTEXT!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)
echo Docker build completed successfully.

echo.
echo ==============================================
echo   Pushing image to registry...
echo   !FULL_IMAGE_NAME!
echo ==============================================
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)
echo Image pushed successfully.

echo.
echo ==============================================
echo   Build ^& Push Complete!
echo   Image URI: !FULL_IMAGE_NAME!
echo ==============================================
echo.
echo Next step: Run scripts\deploy-image.bat to deploy to AWS ECS Fargate.

endlocal
