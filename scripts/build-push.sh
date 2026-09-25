#!/usr/bin/env bash
# =============================================================================
# build-push.sh — Build and push the ResortsLite Docker image
# Usage: ./scripts/build-push.sh
# Run from the repository root directory.
# =============================================================================
set -e
set -o pipefail

PROJECT_NAME="resortslite"
DOCKERFILE="Dockerfile"

echo "=============================================="
echo "  ResortsLite — Docker Build & Push"
echo "=============================================="
echo ""

# -----------------------------------------------------------------------------
# Sanitise image name: lowercase, replace non-alphanumeric with hyphens,
# strip leading/trailing hyphens.
# -----------------------------------------------------------------------------
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

# -----------------------------------------------------------------------------
# Prompt for image tag
# -----------------------------------------------------------------------------
read -rp "Enter image tag [latest]: " RAW_TAG
RAW_TAG="${RAW_TAG:-latest}"
IMAGE_TAG=$(echo "$RAW_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9._-' '-' | sed 's/^-*//;s/-*$//')
IMAGE_TAG="${IMAGE_TAG:-latest}"

echo ""
echo "Image name : $IMAGE_NAME"
echo "Image tag  : $IMAGE_TAG"
echo ""

# -----------------------------------------------------------------------------
# Registry selection
# -----------------------------------------------------------------------------
echo "Select container registry:"
echo "  1) AWS ECR"
echo "  2) Docker Hub"
read -rp "Enter choice [1]: " REGISTRY_CHOICE
REGISTRY_CHOICE="${REGISTRY_CHOICE:-1}"

# -----------------------------------------------------------------------------
# Registry-specific configuration
# -----------------------------------------------------------------------------
if [ "$REGISTRY_CHOICE" = "1" ]; then
    # ---- AWS ECR ----
    echo ""
    read -rp "Enter AWS Region [us-east-1]: " AWS_REGION
    AWS_REGION="${AWS_REGION:-us-east-1}"

    read -rp "Enter AWS Account ID: " ACCOUNT_ID
    if [ -z "$ACCOUNT_ID" ]; then
        echo "Fetching AWS Account ID..."
        ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
    fi

    ECR_REPO="${IMAGE_NAME}"
    REGISTRY_URL="${ACCOUNT_ID}.dkr.ecr.${AWS_REGION}.amazonaws.com"
    FULL_IMAGE_NAME="${REGISTRY_URL}/${ECR_REPO}:${IMAGE_TAG}"

    echo ""
    echo "Registry   : AWS ECR"
    echo "Region     : $AWS_REGION"
    echo "Account    : $ACCOUNT_ID"
    echo "Full image : $FULL_IMAGE_NAME"
    echo ""

    # Authenticate with ECR
    echo "Authenticating with AWS ECR..."
    aws ecr get-login-password --region "$AWS_REGION" | \
        docker login --username AWS --password-stdin "$REGISTRY_URL"
    echo "ECR login successful."

    # Auto-create ECR repository if it does not exist
    echo "Checking ECR repository '$ECR_REPO'..."
    aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || \
        aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"
    echo "ECR repository ready."

elif [ "$REGISTRY_CHOICE" = "2" ]; then
    # ---- Docker Hub ----
    echo ""
    read -rp "Enter Docker Hub username: " DOCKER_USERNAME
    read -rsp "Enter Docker Hub password/token: " DOCKER_PASSWORD
    echo ""
    read -rp "Enter Docker Hub namespace [$DOCKER_USERNAME]: " DOCKER_NAMESPACE
    DOCKER_NAMESPACE="${DOCKER_NAMESPACE:-$DOCKER_USERNAME}"

    FULL_IMAGE_NAME="${DOCKER_NAMESPACE}/${IMAGE_NAME}:${IMAGE_TAG}"

    echo ""
    echo "Registry   : Docker Hub"
    echo "Full image : $FULL_IMAGE_NAME"
    echo ""

    # Authenticate with Docker Hub
    echo "Authenticating with Docker Hub..."
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    echo "Docker Hub login successful."

else
    echo "ERROR: Invalid registry choice '$REGISTRY_CHOICE'. Exiting."
    exit 1
fi

# -----------------------------------------------------------------------------
# Build the Docker image
# Build context is always the repository root; Dockerfile path is relative.
# -----------------------------------------------------------------------------
echo ""
echo "Building Docker image..."
docker build -f "$DOCKERFILE" -t "$FULL_IMAGE_NAME" .
echo "Build complete: $FULL_IMAGE_NAME"

# Also tag as latest for convenience
if [ "$IMAGE_TAG" != "latest" ]; then
    LATEST_IMAGE="${FULL_IMAGE_NAME%:*}:latest"
    docker tag "$FULL_IMAGE_NAME" "$LATEST_IMAGE"
    echo "Also tagged as: $LATEST_IMAGE"
fi

# -----------------------------------------------------------------------------
# Push the image
# -----------------------------------------------------------------------------
echo ""
echo "Pushing image to registry..."
docker push "$FULL_IMAGE_NAME"
if [ "$IMAGE_TAG" != "latest" ]; then
    docker push "$LATEST_IMAGE"
fi

echo ""
echo "=============================================="
echo "  Build & Push Complete!"
echo "  Image: $FULL_IMAGE_NAME"
echo "=============================================="
