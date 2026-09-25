@echo off
setlocal enabledelayedexpansion

:: ============================================================
:: deploy-image.bat — Deploy ResortsLite to Azure AKS
:: ============================================================

set "NAMESPACE=resortslite"
set "APP_NAME=resortslite"
set "MANIFESTS_DIR=kubernetes"
set "TEMP_DIR=%TEMP%\resortslite-k8s-deploy"

echo ==============================================
echo   ResortsLite — Deploy to Azure AKS
echo ==============================================

:: ---- Azure / AKS credentials ----
set /p "RESOURCE_GROUP=Enter Azure Resource Group name: "
if "!RESOURCE_GROUP!"=="" (
    echo ERROR: Resource group cannot be empty.
    exit /b 1
)

set /p "CLUSTER_NAME=Enter AKS Cluster name: "
if "!CLUSTER_NAME!"=="" (
    echo ERROR: AKS cluster name cannot be empty.
    exit /b 1
)

:: ---- Docker image URI ----
set /p "IMAGE_URI=Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): "
if "!IMAGE_URI!"=="" (
    echo ERROR: Image URI cannot be empty.
    exit /b 1
)

:: ---- Application-specific environment variables ----
echo.
echo --- Application Environment Variables ---
echo (Press Enter to keep default placeholder value)

set /p "REDIS_HOST_VAL=Enter REDIS_HOST (Azure Cache for Redis hostname) [localhost]: "
if "!REDIS_HOST_VAL!"=="" set "REDIS_HOST_VAL=localhost"

set /p "REDIS_PORT_VAL=Enter REDIS_PORT [6380 for Azure SSL]: "
if "!REDIS_PORT_VAL!"=="" set "REDIS_PORT_VAL=6380"

set /p "REDIS_PASSWORD_VAL=Enter REDIS_PASSWORD (Azure Cache for Redis access key): "
if "!REDIS_PASSWORD_VAL!"=="" set "REDIS_PASSWORD_VAL="

set /p "REDIS_SSL_VAL=Enter REDIS_SSL [true]: "
if "!REDIS_SSL_VAL!"=="" set "REDIS_SSL_VAL=true"

set /p "PAYMENT_API_URL_VAL=Enter PAYMENT_API_URL [http://payment-svc.internal:9090/payments/charge]: "
if "!PAYMENT_API_URL_VAL!"=="" set "PAYMENT_API_URL_VAL=http://payment-svc.internal:9090/payments/charge"

set /p "REPORT_BASE_PATH_VAL=Enter REPORT_BASE_PATH [/var/reports]: "
if "!REPORT_BASE_PATH_VAL!"=="" set "REPORT_BASE_PATH_VAL=/var/reports"

set /p "BACKUP_PATH_VAL=Enter BACKUP_PATH [/var/backups/nightly]: "
if "!BACKUP_PATH_VAL!"=="" set "BACKUP_PATH_VAL=/var/backups/nightly"

:: ---- Configure kubectl for AKS ----
echo.
echo Configuring kubectl for AKS cluster: !CLUSTER_NAME! ...
az aks get-credentials --resource-group !RESOURCE_GROUP! --name !CLUSTER_NAME! --overwrite-existing
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to get AKS credentials.
    exit /b 1
)

echo Verifying cluster connectivity ...
kubectl cluster-info
if !ERRORLEVEL! neq 0 (
    echo ERROR: Cannot connect to AKS cluster.
    exit /b 1
)

:: ---- Copy manifests to temp directory ----
echo.
echo Updating Kubernetes manifests with deployment values ...
if exist "!TEMP_DIR!" rmdir /s /q "!TEMP_DIR!"
xcopy /s /e /i /q "!MANIFESTS_DIR!" "!TEMP_DIR!"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to copy manifests.
    exit /b 1
)

:: ---- Patch manifests using PowerShell ----
powershell -NoProfile -Command ^
  "(Get-Content '!TEMP_DIR!\deployment.yaml') ^
   -replace '\{\{IMAGE_URI\}\}','!IMAGE_URI!' ^
   -replace '\{\{REDIS_HOST\}\}','!REDIS_HOST_VAL!' ^
   -replace '\{\{REDIS_PORT\}\}','!REDIS_PORT_VAL!' ^
   -replace '\{\{REDIS_PASSWORD\}\}','!REDIS_PASSWORD_VAL!' ^
   -replace '\{\{REDIS_SSL\}\}','!REDIS_SSL_VAL!' ^
   -replace '\{\{PAYMENT_API_URL\}\}','!PAYMENT_API_URL_VAL!' ^
   -replace '\{\{REPORT_BASE_PATH\}\}','!REPORT_BASE_PATH_VAL!' ^
   -replace '\{\{BACKUP_PATH\}\}','!BACKUP_PATH_VAL!' ^
   | Set-Content '!TEMP_DIR!\deployment.yaml'"
if !ERRORLEVEL! neq 0 (
    echo ERROR: Failed to patch deployment manifest.
    exit /b 1
)

:: ---- Apply manifests in order ----
echo.
echo Applying Kubernetes manifests ...

echo   [1/4] Applying namespace ...
kubectl apply -f "!TEMP_DIR!\namespace.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply namespace. & exit /b 1 )

echo   [2/4] Applying deployment ...
kubectl apply -f "!TEMP_DIR!\deployment.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply deployment. & exit /b 1 )

echo   [3/4] Applying service ...
kubectl apply -f "!TEMP_DIR!\service.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply service. & exit /b 1 )

echo   [4/4] Applying ingress ...
kubectl apply -f "!TEMP_DIR!\ingress.yaml"
if !ERRORLEVEL! neq 0 ( echo ERROR: Failed to apply ingress. & exit /b 1 )

:: ---- Wait for rollout ----
echo.
echo Waiting for deployment rollout ...
kubectl rollout status deployment/!APP_NAME! -n !NAMESPACE! --timeout=300s
if !ERRORLEVEL! neq 0 (
    echo ERROR: Deployment rollout failed or timed out.
    echo Run: kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!
    exit /b 1
)

:: ---- Verify resources ----
echo.
echo Verifying deployed resources ...
kubectl get pods,svc,ingress -n !NAMESPACE!

echo.
echo ==============================================
echo   DEPLOYMENT COMPLETE!
echo   Namespace : !NAMESPACE!
echo   Image     : !IMAGE_URI!
echo   App URL   : http://resortslite.example.com
echo ==============================================
echo.
echo Rollback command (if needed):
echo   kubectl rollout undo deployment/!APP_NAME! -n !NAMESPACE!

:: Cleanup temp files
rmdir /s /q "!TEMP_DIR!"

endlocal
