@echo off
setlocal enabledelayedexpansion

:: =============================================================================
:: build-push.bat — Build and push the ResortsLite Docker image (Windows)
:: Usage: scripts\build-push.bat
:: Run from the repository root directory.
:: =============================================================================

set PROJECT_NAME=resortslite
set DOCKERFILE=Dockerfile

echo ==============================================
echo   ResortsLite - Docker Build and Push
echo ==============================================
echo.

:: Sanitise image name (PowerShell for reliable string manipulation)
for /f "delims=" %%i in ('powershell -NoProfile -Command "$n = 'resortslite'; $n = $n.ToLower() -replace '[^a-z0-9]+','-'; $n = $n.Trim('-'); Write-Output $n"') do set IMAGE_NAME=%%i

:: Prompt for image tag
set /p RAW_TAG="Enter image tag [latest]: "
if "!RAW_TAG!"=="" set RAW_TAG=latest
for /f "delims=" %%i in ('powershell -NoProfile -Command "$t = '!RAW_TAG!'; $t = $t.ToLower() -replace '[^a-z0-9._-]+','-'; $t = $t.Trim('-'); if ($t -eq '') { $t = 'latest' }; Write-Output $t"') do set IMAGE_TAG=%%i

echo.
echo Image name : !IMAGE_NAME!
echo Image tag  : !IMAGE_TAG!
echo.

:: Registry selection
echo Select container registry:
echo   1) AWS ECR
echo   2) Docker Hub
set /p REGISTRY_CHOICE="Enter choice [1]: "
if "!REGISTRY_CHOICE!"=="" set REGISTRY_CHOICE=1

:: ============================================================================
:: AWS ECR
:: ============================================================================
if "!REGISTRY_CHOICE!"=="1" (
    echo.
    set /p AWS_REGION="Enter AWS Region [us-east-1]: "
    if "!AWS_REGION!"=="" set AWS_REGION=us-east-1

    set /p ACCOUNT_ID="Enter AWS Account ID (leave blank to auto-detect): "
    if "!ACCOUNT_ID!"=="" (
        echo Fetching AWS Account ID...
        for /f "delims=" %%a in ('aws sts get-caller-identity --query Account --output text') do set ACCOUNT_ID=%%a
    )

    set ECR_REPO=!IMAGE_NAME!
    set REGISTRY_URL=!ACCOUNT_ID!.dkr.ecr.!AWS_REGION!.amazonaws.com
    set FULL_IMAGE_NAME=!REGISTRY_URL!/!ECR_REPO!:!IMAGE_TAG!

    echo.
    echo Registry   : AWS ECR
    echo Region     : !AWS_REGION!
    echo Account    : !ACCOUNT_ID!
    echo Full image : !FULL_IMAGE_NAME!
    echo.

    echo Authenticating with AWS ECR...
    aws ecr get-login-password --region !AWS_REGION! | docker login --username AWS --password-stdin !REGISTRY_URL!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: ECR login failed.
        exit /b 1
    )
    echo ECR login successful.

    echo Checking ECR repository '!ECR_REPO!'...
    aws ecr describe-repositories --repository-names !ECR_REPO! --region !AWS_REGION! >nul 2>&1
    if !ERRORLEVEL! neq 0 (
        echo Creating ECR repository...
        aws ecr create-repository --repository-name !ECR_REPO! --region !AWS_REGION!
        if !ERRORLEVEL! neq 0 (
            echo ERROR: Failed to create ECR repository.
            exit /b 1
        )
    )
    echo ECR repository ready.
    goto BUILD
)

:: ============================================================================
:: Docker Hub
:: ============================================================================
if "!REGISTRY_CHOICE!"=="2" (
    echo.
    set /p DOCKER_USERNAME="Enter Docker Hub username: "
    set /p DOCKER_PASSWORD="Enter Docker Hub password/token: "
    set /p DOCKER_NAMESPACE="Enter Docker Hub namespace [!DOCKER_USERNAME!]: "
    if "!DOCKER_NAMESPACE!"=="" set DOCKER_NAMESPACE=!DOCKER_USERNAME!

    set FULL_IMAGE_NAME=!DOCKER_NAMESPACE!/!IMAGE_NAME!:!IMAGE_TAG!

    echo.
    echo Registry   : Docker Hub
    echo Full image : !FULL_IMAGE_NAME!
    echo.

    echo Authenticating with Docker Hub...
    echo !DOCKER_PASSWORD! | docker login --username !DOCKER_USERNAME! --password-stdin
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker Hub login failed.
        exit /b 1
    )
    echo Docker Hub login successful.
    goto BUILD
)

echo ERROR: Invalid registry choice '!REGISTRY_CHOICE!'. Exiting.
exit /b 1

:: ============================================================================
:BUILD
:: ============================================================================
echo.
echo Building Docker image...
docker build -f !DOCKERFILE! -t !FULL_IMAGE_NAME! .
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker build failed.
    exit /b 1
)
echo Build complete: !FULL_IMAGE_NAME!

:: Tag as latest if a different tag was specified
if not "!IMAGE_TAG!"=="latest" (
    for /f "tokens=1 delims=:" %%a in ("!FULL_IMAGE_NAME!") do set BASE_IMAGE=%%a
    set LATEST_IMAGE=!REGISTRY_URL!/!ECR_REPO!:latest
    if "!REGISTRY_CHOICE!"=="2" set LATEST_IMAGE=!DOCKER_NAMESPACE!/!IMAGE_NAME!:latest
    docker tag !FULL_IMAGE_NAME! !LATEST_IMAGE!
    echo Also tagged as: !LATEST_IMAGE!
)

:: ============================================================================
:: Push
:: ============================================================================
echo.
echo Pushing image to registry...
docker push !FULL_IMAGE_NAME!
if !ERRORLEVEL! neq 0 (
    echo ERROR: Docker push failed.
    exit /b 1
)

if not "!IMAGE_TAG!"=="latest" (
    docker push !LATEST_IMAGE!
    if !ERRORLEVEL! neq 0 (
        echo ERROR: Docker push of latest tag failed.
        exit /b 1
    )
)

echo.
echo ==============================================
echo   Build and Push Complete!
echo   Image: !FULL_IMAGE_NAME!
echo ==============================================

endlocal
