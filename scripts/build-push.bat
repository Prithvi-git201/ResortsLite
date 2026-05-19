@echo off
setlocal enabledelayedexpansion

REM Build and Push Script for ResortsLite Application (Windows)
REM This script builds the Docker image and pushes it to a container registry

echo ==========================================
echo ResortsLite - Build and Push Docker Image
echo ==========================================
echo.

REM Project configuration
set PROJECT_NAME=resortslite

REM Sanitize project name for Docker tag (lowercase, hyphenate)
set IMAGE_NAME=%PROJECT_NAME%
for %%i in (A B C D E F G H I J K L M N O P Q R S T U V W X Y Z) do (
    set IMAGE_NAME=!IMAGE_NAME:%%i=%%i!
)
set IMAGE_NAME=%IMAGE_NAME: =-%
set IMAGE_NAME=%IMAGE_NAME%

echo Project: %PROJECT_NAME%
echo Image name: %IMAGE_NAME%
echo.

REM Prompt for image tag
set /p IMAGE_TAG="Enter image tag (default: latest): "
if "!IMAGE_TAG!"=="" set IMAGE_TAG=latest

echo Using tag: !IMAGE_TAG!
echo.

REM Prompt for registry selection
echo Select container registry:
echo 1. AWS ECR (Elastic Container Registry)
echo 2. Docker Hub
set /p REGISTRY_CHOICE="Enter choice (1 or 2): "

if "!REGISTRY_CHOICE!"=="1" (
    echo.
    echo === AWS ECR Configuration ===
    
    REM Prompt for AWS region
    set /p AWS_REGION="Enter AWS region (default: us-east-1): "
    if "!AWS_REGION!"=="" set AWS_REGION=us-east-1
    
    REM Prompt for AWS account ID
    set /p AWS_ACCOUNT_ID="Enter AWS account ID: "
    
    if "!AWS_ACCOUNT_ID!"=="" (
        echo Error: AWS account ID is required
        exit /b 1
    )
    
    REM Prompt for ECR repository name
    set /p ECR_REPO="Enter ECR repository name (default: %IMAGE_NAME%): "
    if "!ECR_REPO!"=="" set ECR_REPO=%IMAGE_NAME%
    
    REM Construct registry URL
    set REGISTRY_URL=!AWS_ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com
    set FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!
    
    echo.
    echo === Authenticating with AWS ECR ===
    
    REM Get ECR login password and authenticate
    for /f "delims=" %%i in ('aws ecr get-login-password --region !AWS_REGION!') do set ECR_PASSWORD=%%i
    echo !ECR_PASSWORD! | docker login --username AWS --password-stdin !REGISTRY_URL!
    
    if !ERRORLEVEL! neq 0 (
        echo Error: ECR authentication failed
        exit /b 1
    )
    
    echo ECR authentication successful
    
    REM Check if repository exists, create if it doesn't
    echo.
    echo === Checking ECR repository ===
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    
    if !ERRORLEVEL! neq 0 (
        echo Repository does not exist. Creating ECR repository: !ECR_REPO!
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo Error: Failed to create ECR repository
            exit /b 1
        )
        echo ECR repository created successfully
    )
    
) else if "!REGISTRY_CHOICE!"=="2" (
    echo.
    echo === Docker Hub Configuration ===
    
    REM Prompt for Docker Hub username
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    
    if "!DOCKER_USERNAME!"=="" (
        echo Error: Docker Hub username is required
        exit /b 1
    )
    
    REM Prompt for Docker Hub password
    set /p DOCKER_PASSWORD="Enter Docker Hub password: "
    
    if "!DOCKER_PASSWORD!"=="" (
        echo Error: Docker Hub password is required
        exit /b 1
    )
    
    REM Construct full image name
    set FULL_IMAGE_NAME=!DOCKER_USERNAME!/%IMAGE_NAME%:!IMAGE_TAG!
    
    echo.
    echo === Authenticating with Docker Hub ===
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    
    if !ERRORLEVEL! neq 0 (
        echo Error: Docker Hub authentication failed
        exit /b 1
    )
    
    echo Docker Hub authentication successful
    
) else (
    echo Error: Invalid choice. Please select 1 or 2
    exit /b 1
)

echo.
echo === Building Docker Image ===
echo Image: !FULL_IMAGE_NAME!
echo.

docker build -t "!FULL_IMAGE_NAME!" .

if !ERRORLEVEL! neq 0 (
    echo Error: Docker build failed
    exit /b 1
)

echo.
echo === Docker Build Successful ===
echo.

echo === Pushing Image to Registry ===
docker push "!FULL_IMAGE_NAME!"

if !ERRORLEVEL! neq 0 (
    echo Error: Docker push failed
    exit /b 1
)

echo.
echo ==========================================
echo Build and Push Completed Successfully!
echo ==========================================
echo Image: !FULL_IMAGE_NAME!
echo.
echo You can now deploy this image using the deploy-image script
echo.

endlocal
