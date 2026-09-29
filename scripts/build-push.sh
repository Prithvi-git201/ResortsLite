#!/bin/bash
# =============================================================================
# build-push.sh — Build and push ResortsLite Docker image
# Supports: AWS ECR and Docker Hub
# Usage: ./scripts/build-push.sh  (run from repository root)
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortslite"
DOCKERFILE_PATH="Dockerfile"
BUILD_CONTEXT="."

echo "=============================================="
echo "  ResortsLite — Docker Build & Push Script"
echo "=============================================="
echo ""

# ------------------------------------------------------------------------------
# Sanitize image name: lowercase, replace non-alphanumeric with hyphens,
# trim leading/trailing hyphens
# ------------------------------------------------------------------------------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# ------------------------------------------------------------------------------
# Prompt for image tag
# ------------------------------------------------------------------------------
read -rp "Enter image tag [latest]: " INPUT_TAG
INPUT_TAG=$(echo "${INPUT_TAG:-latest}" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${INPUT_TAG:-latest}"
echo "Using image tag: ${IMAGE_TAG}"
echo ""

# ------------------------------------------------------------------------------
# Registry selection
# ------------------------------------------------------------------------------
echo "Select container registry:"
echo "  1. AWS ECR"
echo "  2. Docker Hub"
read -rp "Enter choice [1]: " REGISTRY_CHOICE
REGISTRY_CHOICE="${REGISTRY_CHOICE:-1}"

# ------------------------------------------------------------------------------
# Registry-specific configuration and authentication
# ------------------------------------------------------------------------------
if [ "$REGISTRY_CHOICE" = "1" ]; then
  # ---- AWS ECR ----
  echo ""
  echo "--- AWS ECR Configuration ---"
  read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
  AWS_REGION="${AWS_REGION:-us-east-1}"

  read -rp "Enter ECR repository name [${IMAGE_NAME}]: " ECR_REPO
  ECR_REPO="${ECR_REPO:-${IMAGE_NAME}}"

  # Derive account ID and registry URL
  echo "Retrieving AWS Account ID..."
  ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
  REGISTRY_URL="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
  FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

  echo "Logging in to ECR..."
  aws ecr get-login-password --region "${AWS_REGION}" | \
    docker login --username AWS --password-stdin "${REGISTRY_URL}"

  # Auto-create ECR repository if it does not exist
  echo "Checking ECR repository..."
  aws ecr describe-repositories --repository-names "${ECR_REPO}" --region "${AWS_REGION}" >/dev/null 2>&1 || \
    aws ecr create-repository --repository-name "${ECR_REPO}" --region "${AWS_REGION}"
  echo "ECR repository ready: ${ECR_REPO}"

elif [ "$REGISTRY_CHOICE" = "2" ]; then
  # ---- Docker Hub ----
  echo ""
  echo "--- Docker Hub Configuration ---"
  read -rp "Enter Docker Hub username: " DOCKER_USERNAME
  read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
  echo ""
  read -rp "Enter Docker Hub repository [${DOCKER_USERNAME}/${IMAGE_NAME}]: " DH_REPO
  DH_REPO="${DH_REPO:-${DOCKER_USERNAME}/${IMAGE_NAME}}"

  REGISTRY_URL="docker.io"
  FULL_IMAGE_NAME="${DH_REPO}:${IMAGE_TAG}"

  echo "Logging in to Docker Hub..."
  echo "${DOCKER_PASSWORD}" | docker login --username "${DOCKER_USERNAME}" --password-stdin
else
  echo "ERROR: Invalid registry choice '${REGISTRY_CHOICE}'. Exiting."
  exit 1
fi

echo ""
echo "Full image name: ${FULL_IMAGE_NAME}"
echo ""

# ------------------------------------------------------------------------------
# Build Docker image
# ------------------------------------------------------------------------------
echo "Building Docker image..."
docker build \
  -f "${DOCKERFILE_PATH}" \
  -t "${FULL_IMAGE_NAME}" \
  "${BUILD_CONTEXT}"

echo "Build successful: ${FULL_IMAGE_NAME}"
echo ""

# ------------------------------------------------------------------------------
# Push Docker image
# ------------------------------------------------------------------------------
echo "Pushing image to registry..."
docker push "${FULL_IMAGE_NAME}"

echo ""
echo "=============================================="
echo "  Image pushed successfully!"
echo "  ${FULL_IMAGE_NAME}"
echo "=============================================="
