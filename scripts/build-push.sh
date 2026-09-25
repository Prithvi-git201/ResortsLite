#!/bin/bash
# =============================================================================
# build-push.sh — Build and push ResortsLite Docker image
# Supports: AWS ECR and Docker Hub
# Usage: ./scripts/build-push.sh
# Run from repository root directory
# =============================================================================

set -e
set -o pipefail

PROJECT_NAME="resortsLite"
DOCKERFILE_PATH="Dockerfile"
BUILD_CONTEXT="."

echo "=============================================="
echo "  ResortsLite — Docker Build & Push Script"
echo "=============================================="
echo ""

# ------------------------------------------------------------------------------
# Sanitize project name: lowercase, replace non-alphanumeric with hyphens,
# trim leading/trailing hyphens
# ------------------------------------------------------------------------------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')
echo "Image name (sanitized): $IMAGE_NAME"
echo ""

# ------------------------------------------------------------------------------
# Prompt for image tag
# ------------------------------------------------------------------------------
read -rp "Enter image tag [latest]: " IMAGE_TAG_INPUT
IMAGE_TAG=$(echo "$IMAGE_TAG_INPUT" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
if [ -z "$IMAGE_TAG" ]; then
  IMAGE_TAG="latest"
fi
echo "Image tag: $IMAGE_TAG"
echo ""

# ------------------------------------------------------------------------------
# Registry selection
# ------------------------------------------------------------------------------
echo "Select container registry:"
echo "  1. AWS ECR (Elastic Container Registry)"
echo "  2. Docker Hub"
echo ""
read -rp "Enter choice [1 or 2]: " REGISTRY_CHOICE

case "$REGISTRY_CHOICE" in
  1)
    echo ""
    echo "--- AWS ECR Configuration ---"
    read -rp "Enter AWS Region (e.g. us-east-1): " AWS_REGION
    read -rp "Enter ECR repository name [$IMAGE_NAME]: " ECR_REPO_INPUT
    ECR_REPO="${ECR_REPO_INPUT:-$IMAGE_NAME}"

    echo ""
    echo "Retrieving AWS Account ID..."
    ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
    echo "Account ID: $ACCOUNT_ID"

    REGISTRY_URL="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

    echo ""
    echo "Authenticating with ECR..."
    aws ecr get-login-password --region "$AWS_REGION" | \
      docker login --username AWS --password-stdin "$REGISTRY_URL"
    echo "ECR login successful."

    echo ""
    echo "Checking if ECR repository exists..."
    aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || \
      aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"
    echo "ECR repository ready: $ECR_REPO"
    ;;

  2)
    echo ""
    echo "--- Docker Hub Configuration ---"
    read -rp "Enter Docker Hub username: " DOCKER_USERNAME
    read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo ""
    read -rp "Enter Docker Hub repository name [$DOCKER_USERNAME/$IMAGE_NAME]: " DOCKERHUB_REPO_INPUT
    DOCKERHUB_REPO="${DOCKERHUB_REPO_INPUT:-$DOCKER_USERNAME/$IMAGE_NAME}"

    FULL_IMAGE_NAME="${DOCKERHUB_REPO}:${IMAGE_TAG}"

    echo ""
    echo "Authenticating with Docker Hub..."
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    echo "Docker Hub login successful."
    ;;

  *)
    echo "ERROR: Invalid choice. Please enter 1 or 2."
    exit 1
    ;;
esac

echo ""
echo "=============================================="
echo "  Building Docker image..."
echo "  Image: $FULL_IMAGE_NAME"
echo "  Dockerfile: $DOCKERFILE_PATH"
echo "  Context: $BUILD_CONTEXT"
echo "=============================================="
docker build -f "$DOCKERFILE_PATH" -t "$FULL_IMAGE_NAME" "$BUILD_CONTEXT"
echo "Docker build completed successfully."

echo ""
echo "=============================================="
echo "  Pushing image to registry..."
echo "  $FULL_IMAGE_NAME"
echo "=============================================="
docker push "$FULL_IMAGE_NAME"
echo "Image pushed successfully."

echo ""
echo "=============================================="
echo "  Build & Push Complete!"
echo "  Image URI: $FULL_IMAGE_NAME"
echo "=============================================="
echo ""
echo "Next step: Run ./scripts/deploy-image.sh to deploy to AWS ECS Fargate."
