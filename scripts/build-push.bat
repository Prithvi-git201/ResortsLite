@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat — Build and push ResortsLite Docker image (Windows)
:: Supports: AWS ECR and Docker Hub
:: Usage: scripts\build-push.bat  (run from repository root)
:: =============================================================================

set "PROJECT_NAME=resortslite"
set "DOCKERFILE_PATH=Dockerfile"
set "BUILD_CONTEXT=."

echo ==============================================
echo   ResortsLite -- Docker Build ^& Push Script
echo ==============================================
echo.

:: ------------------------------------------------------------------------------
:: Sanitize image name using PowerShell
:: ------------------------------------------------------------------------------
for /f "delims=" %%i in ('powershell -NoProfile -Command "$n = 'resortslite'; $n = $n.ToLower() -replace '[^a-z0-9]+','-'; $n = $n.Trim('-'); Write-Output $n"') do set "IMAGE_NAME=%%i"

:: ------------------------------------------------------------------------------
:: Prompt for image tag
:: ------------------------------------------------------------------------------
set /p "INPUT_TAG=Enter image tag [latest]: "
if "!INPUT_TAG!"=="" set "INPUT_TAG=latest"
for /f "delims=" %%t in ('powershell -NoProfile -Command "$t = '!INPUT_TAG!'; $t = $t.ToLower() -replace '[^a-z0-9._-]+','-'; $t = $t.Trim('-'); if ($t -eq '') { $t = 'latest' }; Write-Output $t"') do set "IMAGE_TAG=%%t"
echo Using image tag: !IMAGE_TAG!
echo.

:: ------------------------------------------------------------------------------
:: Registry selection
:: ------------------------------------------------------------------------------
echo Select container registry:
echo   1. AWS ECR
echo   2. Docker Hub
set /p "REGISTRY_CHOICE=Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set "REGISTRY_CHOICE=1"

:: ------------------------------------------------------------------------------
:: Registry-specific configuration and authentication
:: ------------------------------------------------------------------------------
if "!REGISTRY_CHOICE!"=="1" (
    echo.
    echo --- AWS ECR Configuration ---
    set /p "AWS_REGION=Enter AWS Region [us-east-1]: "
    if "!AWS_REGION!"=="" set "AWS_REGION=us-east-1"

    set /p "ECR_REPO=Enter ECR repository name [!IMAGE_NAME!]: "
    if "!ECR_REPO!"=="" set "ECR_REPO=!IMAGE_NAME!"

    echo Retrieving AWS Account ID...
    for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set "ACCOUNT_ID=%%a"
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Failed to retrieve AWS Account ID. Check AWS CLI configuration.
        exit /b 1
    )

    set "REGISTRY_URL=!ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com"
    set "FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!"

    echo Logging in to ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed.
        exit /b 1
    )

    echo Checking ECR repository...
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
    set /p "DOCKER_USERNAME=Enter Docker Hub username: "
    set /p "DOCKER_PASSWORD=Enter Docker Hub password/token: "
    set /p "DH_REPO=Enter Docker Hub repository [!DOCKER_USERNAME!/!IMAGE_NAME!]: "
    if "!DH_REPO!"=="" set "DH_REPO=!DOCKER_USERNAME!/!IMAGE_NAME!"

    set "REGISTRY_URL=docker.io"
    set "FULL_IMAGE_NAME=!DH_REPO!:!IMAGE_TAG!"

    echo Logging in to Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )
) else (
    echo ERROR: Invalid registry choice '!REGISTRY_CHOICE!'. Exiting.
    exit /b 1
)

echo.
echo Full image name: !FULL_IMAGE_NAME!
echo.

:: ------------------------------------------------------------------------------
:: Build Docker image
:: ------------------------------------------------------------------------------
echo Building Docker image...
docker build -f "!DOCKERFILE_PATH!" -t "!FULL_IMAGE_NAME!" "!BUILD_CONTEXT!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)
echo Build successful: !FULL_IMAGE_NAME!
echo.

:: ------------------------------------------------------------------------------
:: Push Docker image
:: ------------------------------------------------------------------------------
echo Pushing image to registry...
docker push "!FULL_IMAGE_NAME!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

echo.
echo ==============================================
echo   Image pushed successfully!
echo   !FULL_IMAGE_NAME!
echo ==============================================

endlocal
