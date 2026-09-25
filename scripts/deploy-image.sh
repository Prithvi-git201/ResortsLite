#!/bin/bash
set -e
set -o pipefail

# ============================================================
# deploy-image.sh — Deploy ResortsLite to Azure AKS
# ============================================================

NAMESPACE="resortslite"
APP_NAME="resortslite"
MANIFESTS_DIR="kubernetes"

echo "=============================================="
echo "  ResortsLite — Deploy to Azure AKS"
echo "=============================================="

# ---- Azure / AKS credentials ----
read -rp "Enter Azure Resource Group name: " RESOURCE_GROUP
if [ -z "$RESOURCE_GROUP" ]; then
  echo "ERROR: Resource group cannot be empty." >&2
  exit 1
fi

read -rp "Enter AKS Cluster name: " CLUSTER_NAME
if [ -z "$CLUSTER_NAME" ]; then
  echo "ERROR: AKS cluster name cannot be empty." >&2
  exit 1
fi

# ---- Docker image URI ----
read -rp "Enter full Docker image URI (e.g. myregistry.azurecr.io/resortslite:latest): " IMAGE_URI
if [ -z "$IMAGE_URI" ]; then
  echo "ERROR: Image URI cannot be empty." >&2
  exit 1
fi

# ---- Application-specific environment variables ----
echo ""
echo "--- Application Environment Variables ---"
echo "(Press Enter to keep placeholder value and configure later)"

read -rp "Enter REDIS_HOST (Azure Cache for Redis hostname): " REDIS_HOST_VAL
REDIS_HOST_VAL="${REDIS_HOST_VAL:-localhost}"

read -rp "Enter REDIS_PORT [6380 for Azure SSL]: " REDIS_PORT_VAL
REDIS_PORT_VAL="${REDIS_PORT_VAL:-6380}"

read -rsp "Enter REDIS_PASSWORD (Azure Cache for Redis access key): " REDIS_PASSWORD_VAL
echo ""
REDIS_PASSWORD_VAL="${REDIS_PASSWORD_VAL:-}"

read -rp "Enter REDIS_SSL [true]: " REDIS_SSL_VAL
REDIS_SSL_VAL="${REDIS_SSL_VAL:-true}"

read -rp "Enter PAYMENT_API_URL [http://payment-svc.internal:9090/payments/charge]: " PAYMENT_API_URL_VAL
PAYMENT_API_URL_VAL="${PAYMENT_API_URL_VAL:-http://payment-svc.internal:9090/payments/charge}"

read -rp "Enter REPORT_BASE_PATH [/var/reports]: " REPORT_BASE_PATH_VAL
REPORT_BASE_PATH_VAL="${REPORT_BASE_PATH_VAL:-/var/reports}"

read -rp "Enter BACKUP_PATH [/var/backups/nightly]: " BACKUP_PATH_VAL
BACKUP_PATH_VAL="${BACKUP_PATH_VAL:-/var/backups/nightly}"

# ---- Configure kubectl for AKS ----
echo ""
echo "Configuring kubectl for AKS cluster: $CLUSTER_NAME ..."
az aks get-credentials --resource-group "$RESOURCE_GROUP" --name "$CLUSTER_NAME" --overwrite-existing

echo "Verifying cluster connectivity ..."
kubectl cluster-info || { echo "ERROR: Cannot connect to AKS cluster." >&2; exit 1; }

# ---- Patch manifests with actual values ----
echo ""
echo "Updating Kubernetes manifests with deployment values ..."

# Work on copies to avoid modifying originals
cp -r "$MANIFESTS_DIR" /tmp/resortslite-k8s-deploy

sed -i "s|{{IMAGE_URI}}|${IMAGE_URI}|g"                         /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_HOST}}|${REDIS_HOST_VAL}|g"                   /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_PORT}}|${REDIS_PORT_VAL}|g"                   /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_PASSWORD}}|${REDIS_PASSWORD_VAL}|g"           /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REDIS_SSL}}|${REDIS_SSL_VAL}|g"                     /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{PAYMENT_API_URL}}|${PAYMENT_API_URL_VAL}|g"         /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{REPORT_BASE_PATH}}|${REPORT_BASE_PATH_VAL}|g"       /tmp/resortslite-k8s-deploy/deployment.yaml
sed -i "s|{{BACKUP_PATH}}|${BACKUP_PATH_VAL}|g"                 /tmp/resortslite-k8s-deploy/deployment.yaml

# ---- Apply manifests in order ----
echo ""
echo "Applying Kubernetes manifests ..."

echo "  [1/4] Applying namespace ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/namespace.yaml

echo "  [2/4] Applying deployment ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/deployment.yaml

echo "  [3/4] Applying service ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/service.yaml

echo "  [4/4] Applying ingress ..."
kubectl apply -f /tmp/resortslite-k8s-deploy/ingress.yaml

# ---- Wait for rollout ----
echo ""
echo "Waiting for deployment rollout ..."
kubectl rollout status deployment/${APP_NAME} -n ${NAMESPACE} --timeout=300s

# ---- Verify resources ----
echo ""
echo "Verifying deployed resources ..."
kubectl get pods,svc,ingress -n ${NAMESPACE}

# ---- Display access URL ----
echo ""
INGRESS_IP=$(kubectl get ingress resortslite-ingress -n ${NAMESPACE} -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "pending")
echo "=============================================="
echo "  DEPLOYMENT COMPLETE!"
echo "  Namespace : ${NAMESPACE}"
echo "  Image     : ${IMAGE_URI}"
echo "  Ingress IP: ${INGRESS_IP}"
echo "  App URL   : http://resortslite.example.com"
echo "=============================================="
echo ""
echo "Rollback command (if needed):"
echo "  kubectl rollout undo deployment/${APP_NAME} -n ${NAMESPACE}"

# Cleanup temp files
rm -rf /tmp/resortslite-k8s-deploy
