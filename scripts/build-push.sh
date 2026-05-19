#!/bin/bash
set -e

# Build and Push Script for ResortsLite Application
# This script builds the Docker image and pushes it to a container registry

echo "=========================================="
echo "ResortsLite - Build and Push Docker Image"
echo "=========================================="
echo ""

# Project configuration
PROJECT_NAME="resortslite"

# Sanitize project name for Docker tag (lowercase, hyphenate)
IMAGE_NAME=$(echo "$PROJECT_NAME" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/^-*//;s/-*$//')

echo "Project: $PROJECT_NAME"
echo "Image name: $IMAGE_NAME"
echo ""

# Prompt for image tag
read -p "Enter image tag (default: latest): " IMAGE_TAG
IMAGE_TAG=${IMAGE_TAG:-latest}

# Sanitize tag
IMAGE_TAG=$(echo "$IMAGE_TAG" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9.-' '-' | sed 's/^-*//;s/-*$//')
echo "Using tag: $IMAGE_TAG"
echo ""

# Prompt for registry selection
echo "Select container registry:"
echo "1. AWS ECR (Elastic Container Registry)"
echo "2. Docker Hub"
read -p "Enter choice (1 or 2): " REGISTRY_CHOICE

if [ "$REGISTRY_CHOICE" = "1" ]; then
    echo ""
    echo "=== AWS ECR Configuration ==="
    
    # Prompt for AWS region
    read -p "Enter AWS region (default: us-east-1): " AWS_REGION
    AWS_REGION=${AWS_REGION:-us-east-1}
    
    # Prompt for AWS account ID
    read -p "Enter AWS account ID: " AWS_ACCOUNT_ID
    
    if [ -z "$AWS_ACCOUNT_ID" ]; then
        echo "Error: AWS account ID is required"
        exit 1
    fi
    
    # Prompt for ECR repository name
    read -p "Enter ECR repository name (default: $IMAGE_NAME): " ECR_REPO
    ECR_REPO=${ECR_REPO:-$IMAGE_NAME}
    
    # Construct registry URL
    REGISTRY_URL="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
    FULL_IMAGE_NAME="$REGISTRY_URL/$ECR_REPO:$IMAGE_TAG"
    
    echo ""
    echo "=== Authenticating with AWS ECR ==="
    aws ecr get-login-password --region "$AWS_REGION" | docker login --username AWS --password-stdin "$REGISTRY_URL"
    
    if [ $? -ne 0 ]; then
        echo "Error: ECR authentication failed"
        exit 1
    fi
    
    echo "ECR authentication successful"
    
    # Check if repository exists, create if it doesn't
    echo ""
    echo "=== Checking ECR repository ==="
    aws ecr describe-repositories --repository-names "$ECR_REPO" --region "$AWS_REGION" >/dev/null 2>&1 || {
        echo "Repository does not exist. Creating ECR repository: $ECR_REPO"
        aws ecr create-repository --repository-name "$ECR_REPO" --region "$AWS_REGION"
        echo "ECR repository created successfully"
    }
    
elif [ "$REGISTRY_CHOICE" = "2" ]; then
    echo ""
    echo "=== Docker Hub Configuration ==="
    
    # Prompt for Docker Hub username
    read -p "Enter Docker Hub username: " DOCKER_USERNAME
    
    if [ -z "$DOCKER_USERNAME" ]; then
        echo "Error: Docker Hub username is required"
        exit 1
    fi
    
    # Prompt for Docker Hub password
    read -sp "Enter Docker Hub password: " DOCKER_PASSWORD
    echo ""
    
    if [ -z "$DOCKER_PASSWORD" ]; then
        echo "Error: Docker Hub password is required"
        exit 1
    fi
    
    # Construct full image name
    FULL_IMAGE_NAME="$DOCKER_USERNAME/$IMAGE_NAME:$IMAGE_TAG"
    
    echo ""
    echo "=== Authenticating with Docker Hub ==="
    echo "$DOCKER_PASSWORD" | docker login --username "$DOCKER_USERNAME" --password-stdin
    
    if [ $? -ne 0 ]; then
        echo "Error: Docker Hub authentication failed"
        exit 1
    fi
    
    echo "Docker Hub authentication successful"
    
else
    echo "Error: Invalid choice. Please select 1 or 2"
    exit 1
fi

echo ""
echo "=== Building Docker Image ==="
echo "Image: $FULL_IMAGE_NAME"
echo ""

docker build -t "$FULL_IMAGE_NAME" .

if [ $? -ne 0 ]; then
    echo "Error: Docker build failed"
    exit 1
fi

echo ""
echo "=== Docker Build Successful ==="
echo ""

echo "=== Pushing Image to Registry ==="
docker push "$FULL_IMAGE_NAME"

if [ $? -ne 0 ]; then
    echo "Error: Docker push failed"
    exit 1
fi

echo ""
echo "=========================================="
echo "Build and Push Completed Successfully!"
echo "=========================================="
echo "Image: $FULL_IMAGE_NAME"
echo ""
echo "You can now deploy this image using the deploy-image script"
echo ""
