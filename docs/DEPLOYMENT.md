# ResortsLite - AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development Setup](#local-development-setup)
4. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
5. [Building and Pushing Docker Image](#building-and-pushing-docker-image)
6. [ECS Task Definition Explained](#ecs-task-definition-explained)
7. [ECS Service Configuration](#ecs-service-configuration)
8. [Deployment to AWS ECS Fargate](#deployment-to-aws-ecs-fargate)
9. [Monitoring and Logging](#monitoring-and-logging)
10. [Troubleshooting](#troubleshooting)
11. [Scaling and Management](#scaling-and-management)
12. [Security Considerations](#security-considerations)

---

## Overview

ResortsLite is a Spring Boot 2.7.18 application built with Java 8 that provides resort booking functionality. This guide covers containerization and deployment to AWS ECS Fargate.

**Technology Stack:**
- Java 8
- Spring Boot 2.7.18
- Maven 3.9.4
- Spring Boot Actuator (Health checks)
- Redis (Session management)
- AWS S3 (File storage)
- H2 Database (In-memory)

**Application Details:**
- Application Port: 8080
- Health Check Endpoint: `/actuator/health`
- Management Endpoints: `/actuator/info`, `/actuator/health`

---

## Prerequisites

### Required Software
- **Docker Desktop** (20.10+)
  - Windows: [Download Docker Desktop for Windows](https://www.docker.com/products/docker-desktop)
  - macOS: [Download Docker Desktop for Mac](https://www.docker.com/products/docker-desktop)
  - Linux: [Install Docker Engine](https://docs.docker.com/engine/install/)

- **AWS CLI** (2.x)
  - Installation: [AWS CLI Installation Guide](https://docs.aws.amazon.com/cli/latest/userguide/getting-started-install.html)
  - Configure credentials: `aws configure`

- **Git** (for version control)

### AWS Account Requirements
- Active AWS account with appropriate permissions
- IAM user with permissions for:
  - ECS (Full access)
  - ECR (Full access)
  - CloudWatch Logs (Write access)
  - VPC (Read access)
  - IAM (Role creation/management)
  - Elastic Load Balancing (if using ALB)

---

## Local Development Setup

### 1. Clone the Repository
```bash
git clone <repository-url>
cd FullComp
```

### 2. Build the Application Locally (Optional)
```bash
# Using Maven
mvn clean package -DskipTests

# The JAR file will be created in target/resortsLite-1.0.0.jar
```

### 3. Run with Docker Compose
```bash
# Build and start the application
docker-compose up --build

# Access the application
# Application: http://localhost:8080
# Health Check: http://localhost:8080/actuator/health
# H2 Console: http://localhost:8080/h2-console
```

### 4. Stop the Application
```bash
docker-compose down
```

---

## AWS ECS Fargate Prerequisites

### 1. VPC Configuration
You need a VPC with at least 2 subnets in different availability zones for high availability.

**Create VPC (if needed):**
```bash
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region us-east-1
```

**Create Subnets:**
```bash
# Subnet 1 (AZ 1)
aws ec2 create-subnet --vpc-id <vpc-id> --cidr-block 10.0.1.0/24 --availability-zone us-east-1a

# Subnet 2 (AZ 2)
aws ec2 create-subnet --vpc-id <vpc-id> --cidr-block 10.0.2.0/24 --availability-zone us-east-1b
```

### 2. Security Group Configuration
Create a security group that allows inbound traffic on port 8080 (application port).

```bash
# Create security group
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "Security group for ResortsLite ECS tasks" \
  --vpc-id <vpc-id>

# Add inbound rule for application port
aws ec2 authorize-security-group-ingress \
  --group-id <security-group-id> \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0

# Add inbound rule for HTTP (if using ALB)
aws ec2 authorize-security-group-ingress \
  --group-id <security-group-id> \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0
```

### 3. IAM Roles

#### ECS Task Execution Role
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create trust policy file (ecs-trust-policy.json)
cat > ecs-trust-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Service": "ecs-tasks.amazonaws.com"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
EOF

# Create the role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document file://ecs-trust-policy.json

# Attach AWS managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

#### ECS Task Role (Optional)
This role grants permissions to the application running in the container (e.g., S3 access).

```bash
# Create task role
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document file://ecs-trust-policy.json

# Attach policies for S3 access
aws iam attach-role-policy \
  --role-name ecsTaskRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
```

### 4. CloudWatch Log Group
Create a log group for application logs.

```bash
aws logs create-log-group --log-group-name /ecs/resortslite --region us-east-1
```

---

## Building and Pushing Docker Image

### Option 1: Using build-push.sh (Linux/macOS)

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run the script
./scripts/build-push.sh
```

**Script will prompt for:**
1. Image tag (default: latest)
2. Registry selection (AWS ECR or Docker Hub)
3. Registry-specific credentials and configuration

### Option 2: Using build-push.bat (Windows)

```cmd
# Run the script
scripts\build-push.bat
```

### Manual Build and Push (AWS ECR)

```bash
# Set variables
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)
ECR_REPO=resortslite
IMAGE_TAG=latest

# Authenticate with ECR
aws ecr get-login-password --region $AWS_REGION | \
  docker login --username AWS --password-stdin \
  $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com

# Create ECR repository (if it doesn't exist)
aws ecr create-repository --repository-name $ECR_REPO --region $AWS_REGION

# Build the image
docker build -t $ECR_REPO:$IMAGE_TAG .

# Tag the image
docker tag $ECR_REPO:$IMAGE_TAG \
  $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG

# Push to ECR
docker push $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$ECR_REPO:$IMAGE_TAG
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) defines how your container runs on ECS Fargate.

### Key Components:

#### 1. Launch Type Configuration
```json
{
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc"
}
```
- **FARGATE**: Serverless compute engine for containers
- **awsvpc**: Each task gets its own elastic network interface

#### 2. CPU and Memory
```json
{
  "cpu": "512",
  "memory": "1024"
}
```
**Valid Fargate CPU/Memory Combinations:**
- CPU: "256" (.25 vCPU) → Memory: 512, 1024, 2048 MB
- CPU: "512" (.5 vCPU) → Memory: 1024, 2048, 3072, 4096 MB
- CPU: "1024" (1 vCPU) → Memory: 2048-8192 MB (increments of 1024)
- CPU: "2048" (2 vCPU) → Memory: 4096-16384 MB (increments of 1024)
- CPU: "4096" (4 vCPU) → Memory: 8192-30720 MB (increments of 1024)

#### 3. Container Definition
```json
{
  "containerDefinitions": [
    {
      "name": "resortslite",
      "image": "{{IMAGE_URI}}",
      "essential": true,
      "portMappings": [
        {
          "containerPort": 8080,
          "protocol": "tcp"
        }
      ]
    }
  ]
}
```

#### 4. Environment Variables
Application configuration is passed via environment variables:
- `SERVER_PORT`: Application port (8080)
- `SPRING_PROFILES_ACTIVE`: Spring profile (docker)
- `JAVA_OPTS`: JVM memory settings
- `REDIS_HOST`, `REDIS_PORT`: Redis configuration
- `AWS_REGION`, `S3_REPORTS_BUCKET`, `S3_BACKUPS_BUCKET`: AWS S3 configuration
- External service endpoints

#### 5. Logging Configuration
```json
{
  "logConfiguration": {
    "logDriver": "awslogs",
    "options": {
      "awslogs-group": "/ecs/resortslite",
      "awslogs-region": "us-east-1",
      "awslogs-stream-prefix": "ecs"
    }
  }
}
```

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) manages the deployment and scaling of tasks.

### Key Components:

#### 1. Service Configuration
```json
{
  "serviceName": "resortslite-service",
  "desiredCount": 2,
  "launchType": "FARGATE"
}
```
- **desiredCount**: Number of task instances to run (2 for high availability)

#### 2. Network Configuration
```json
{
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-xxx", "subnet-yyy"],
      "securityGroups": ["sg-xxx"],
      "assignPublicIp": "ENABLED"
    }
  }
}
```

#### 3. Deployment Configuration
```json
{
  "deploymentConfiguration": {
    "maximumPercent": 200,
    "minimumHealthyPercent": 50,
    "deploymentCircuitBreaker": {
      "enable": true,
      "rollback": true
    }
  }
}
```
- **maximumPercent**: Maximum tasks during deployment (200% = 4 tasks for 2 desired)
- **minimumHealthyPercent**: Minimum healthy tasks (50% = 1 task for 2 desired)
- **deploymentCircuitBreaker**: Automatic rollback on failure

#### 4. Load Balancer (Optional)
```json
{
  "loadBalancers": [
    {
      "targetGroupArn": "arn:aws:elasticloadbalancing:...",
      "containerName": "resortslite",
      "containerPort": 8080
    }
  ],
  "healthCheckGracePeriodSeconds": 300
}
```

---

## Deployment to AWS ECS Fargate

### Option 1: Using deploy-image.sh (Linux/macOS)

```bash
# Make the script executable
chmod +x scripts/deploy-image.sh

# Run the script
./scripts/deploy-image.sh
```

### Option 2: Using deploy-image.bat (Windows)

```cmd
# Run the script
scripts\deploy-image.bat
```

### What the Deployment Script Does:

1. **Validates AWS credentials** and gets account ID
2. **Creates or verifies ECS cluster** exists
3. **Prompts for network configuration** (VPC, subnets, security group)
4. **Prompts for Docker image URI** from ECR
5. **Optionally creates Application Load Balancer** with target group
6. **Prompts for application configuration** (Redis, S3, external services)
7. **Creates CloudWatch log group** for application logs
8. **Registers task definition** with ECS
9. **Creates or updates ECS service**
10. **Waits for service to stabilize**
11. **Displays deployment status** and access information

### Manual Deployment Steps

If you prefer manual deployment:

```bash
# 1. Register task definition
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1

# 2. Create ECS cluster (if needed)
aws ecs create-cluster --cluster-name resortslite-cluster --region us-east-1

# 3. Create service
aws ecs create-service \
  --cli-input-json file://ecs/service-definition.json \
  --region us-east-1

# 4. Wait for service to stabilize
aws ecs wait services-stable \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1
```

---

## Monitoring and Logging

### CloudWatch Logs

**View logs in real-time:**
```bash
aws logs tail /ecs/resortslite --follow --region us-east-1
```

**View logs for specific task:**
```bash
aws logs tail /ecs/resortslite --follow --filter-pattern "task-id" --region us-east-1
```

**Access logs via AWS Console:**
1. Navigate to CloudWatch → Log groups
2. Select `/ecs/resortslite`
3. View log streams for each task

### Service Monitoring

**Check service status:**
```bash
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1
```

**List running tasks:**
```bash
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --region us-east-1
```

**Describe task details:**
```bash
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1
```

### Health Checks

**Application health endpoint:**
```bash
# If using load balancer
curl http://<alb-dns-name>/actuator/health

# Direct task access (if public IP assigned)
curl http://<task-public-ip>:8080/actuator/health
```

**Expected response:**
```json
{
  "status": "UP",
  "components": {
    "diskSpace": {"status": "UP"},
    "ping": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}
```

---

## Troubleshooting

### Common Issues and Solutions

#### 1. Task Fails to Start

**Symptoms:**
- Tasks start and immediately stop
- Status shows "STOPPED" with exit code

**Troubleshooting:**
```bash
# Check task stopped reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1 \
  --query 'tasks[0].stoppedReason'

# Check CloudWatch logs
aws logs tail /ecs/resortslite --since 10m --region us-east-1
```

**Common causes:**
- Invalid environment variables
- Missing IAM permissions
- Image pull errors (check ECR permissions)
- Application startup errors (check logs)

#### 2. Cannot Pull Image from ECR

**Error:** `CannotPullContainerError`

**Solution:**
```bash
# Verify ECR repository exists
aws ecr describe-repositories --repository-names resortslite --region us-east-1

# Verify task execution role has ECR permissions
aws iam get-role --role-name ecsTaskExecutionRole

# Verify image exists in ECR
aws ecr describe-images --repository-name resortslite --region us-east-1
```

#### 3. Service Not Reaching Steady State

**Symptoms:**
- Service stuck in "DRAINING" or "PENDING"
- Tasks repeatedly starting and stopping

**Troubleshooting:**
```bash
# Check service events
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query 'services[0].events[0:10]'
```

**Common causes:**
- Health check failures (check `/actuator/health` endpoint)
- Insufficient CPU/memory (increase task resources)
- Network connectivity issues (check security groups)
- Load balancer target group health checks failing

#### 4. Network Connectivity Issues

**Symptoms:**
- Cannot access application via load balancer
- Tasks cannot connect to external services (Redis, S3)

**Solution:**
```bash
# Verify security group rules
aws ec2 describe-security-groups --group-ids <security-group-id>

# Verify subnet route tables
aws ec2 describe-route-tables --filters "Name=association.subnet-id,Values=<subnet-id>"

# Verify NAT gateway (if using private subnets)
aws ec2 describe-nat-gateways --filter "Name=subnet-id,Values=<subnet-id>"
```

**Check:**
- Security group allows inbound traffic on port 8080
- Security group allows outbound traffic to Redis, S3
- Subnets have internet gateway or NAT gateway attached
- VPC DNS resolution is enabled

#### 5. High Memory Usage / OOM Errors

**Symptoms:**
- Tasks stop with exit code 137
- CloudWatch logs show OutOfMemoryError

**Solution:**
```bash
# Increase task memory in task definition
# Edit ecs/task-definition.json:
{
  "cpu": "1024",
  "memory": "2048"
}

# Adjust JVM heap size in environment variables:
{
  "name": "JAVA_OPTS",
  "value": "-Xmx1536m -Xms768m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
}

# Re-register task definition and update service
```

#### 6. Redis Connection Failures

**Symptoms:**
- Application logs show Redis connection errors
- Health check shows Redis component DOWN

**Solution:**
```bash
# Verify Redis endpoint is accessible
# Check security group allows outbound traffic to Redis port (6379)

# Test Redis connectivity from task
aws ecs execute-command \
  --cluster resortslite-cluster \
  --task <task-id> \
  --container resortslite \
  --interactive \
  --command "/bin/sh"

# Inside container:
# telnet <redis-host> 6379
```

---

## Scaling and Management

### Manual Scaling

**Update desired count:**
```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

**Create auto scaling target:**
```bash
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10 \
  --region us-east-1
```

**Create scaling policy (CPU-based):**
```bash
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name cpu-scaling-policy \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleInCooldown": 300,
    "ScaleOutCooldown": 60
  }' \
  --region us-east-1
```

### Blue/Green Deployments

For zero-downtime deployments, use AWS CodeDeploy with ECS:

1. Create CodeDeploy application and deployment group
2. Configure deployment settings (linear, canary, all-at-once)
3. Deploy new task definition revision
4. CodeDeploy manages traffic shifting

### Rolling Updates

**Update service with new task definition:**
```bash
# Register new task definition revision
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1

# Update service to use new revision
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:2 \
  --region us-east-1
```

**ECS will:**
1. Start new tasks with new task definition
2. Wait for health checks to pass
3. Drain connections from old tasks
4. Stop old tasks

---

## Security Considerations

### 1. IAM Roles and Permissions

**Principle of Least Privilege:**
- Task execution role: Only ECR pull and CloudWatch logs write
- Task role: Only specific S3 buckets and required AWS services

**Example task role policy:**
```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:PutObject"
      ],
      "Resource": [
        "arn:aws:s3:::resort-reports-bucket/*",
        "arn:aws:s3:::resort-backups-bucket/*"
      ]
    }
  ]
}
```

### 2. Network Security

**Security Group Best Practices:**
- Restrict inbound traffic to specific ports (8080)
- Use security group references instead of CIDR blocks
- Separate security groups for ALB and ECS tasks

**Example security group rules:**
```bash
# ALB security group - allow HTTP from internet
aws ec2 authorize-security-group-ingress \
  --group-id <alb-sg-id> \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0

# ECS task security group - allow traffic only from ALB
aws ec2 authorize-security-group-ingress \
  --group-id <ecs-sg-id> \
  --protocol tcp \
  --port 8080 \
  --source-group <alb-sg-id>
```

### 3. Secrets Management

**Use AWS Secrets Manager or Parameter Store:**

```bash
# Store Redis password in Secrets Manager
aws secretsmanager create-secret \
  --name resortslite/redis-password \
  --secret-string "your-redis-password" \
  --region us-east-1

# Reference in task definition
{
  "secrets": [
    {
      "name": "REDIS_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789:secret:resortslite/redis-password"
    }
  ]
}
```

**Update task execution role:**
```bash
# Add Secrets Manager permissions
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

### 4. Container Image Security

**Best Practices:**
- Use official base images (eclipse-temurin)
- Run as non-root user (implemented in Dockerfile)
- Scan images for vulnerabilities
- Keep base images updated

**Scan image with ECR:**
```bash
# Enable image scanning
aws ecr put-image-scanning-configuration \
  --repository-name resortslite \
  --image-scanning-configuration scanOnPush=true \
  --region us-east-1

# View scan results
aws ecr describe-image-scan-findings \
  --repository-name resortslite \
  --image-id imageTag=latest \
  --region us-east-1
```

### 5. Logging and Monitoring

**Enable CloudTrail for API auditing:**
```bash
aws cloudtrail create-trail \
  --name resortslite-trail \
  --s3-bucket-name <cloudtrail-bucket> \
  --region us-east-1

aws cloudtrail start-logging --name resortslite-trail
```

**Set up CloudWatch alarms:**
```bash
# CPU utilization alarm
aws cloudwatch put-metric-alarm \
  --alarm-name resortslite-high-cpu \
  --alarm-description "Alert when CPU exceeds 80%" \
  --metric-name CPUUtilization \
  --namespace AWS/ECS \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --dimensions Name=ServiceName,Value=resortslite-service Name=ClusterName,Value=resortslite-cluster
```

### 6. Data Encryption

**Encryption at rest:**
- ECR images: Encrypted by default with AWS managed keys
- CloudWatch Logs: Enable encryption with KMS
- S3 buckets: Enable default encryption

**Encryption in transit:**
- Use HTTPS for all external communications
- Configure ALB with SSL/TLS certificate
- Use TLS for Redis connections

---

## Additional Resources

### AWS Documentation
- [ECS Fargate Documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [ECS Task Definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [ECS Service Definition](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service_definition_parameters.html)
- [ECR User Guide](https://docs.aws.amazon.com/AmazonECR/latest/userguide/what-is-ecr.html)

### Spring Boot Resources
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Spring Boot Docker](https://spring.io/guides/gs/spring-boot-docker/)
- [Spring Boot Production Ready](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints)

### Docker Resources
- [Dockerfile Best Practices](https://docs.docker.com/develop/develop-images/dockerfile_best-practices/)
- [Multi-stage Builds](https://docs.docker.com/build/building/multi-stage/)

---

## Support and Maintenance

### Regular Maintenance Tasks

1. **Update base images** regularly for security patches
2. **Review CloudWatch logs** for errors and warnings
3. **Monitor resource utilization** and adjust task resources
4. **Update dependencies** in pom.xml for security fixes
5. **Review IAM policies** and remove unused permissions
6. **Rotate secrets** regularly (Redis passwords, API keys)
7. **Test disaster recovery** procedures

### Cost Optimization

1. **Right-size task resources** based on actual usage
2. **Use Fargate Spot** for non-critical workloads
3. **Implement auto-scaling** to match demand
4. **Review CloudWatch Logs retention** settings
5. **Delete unused ECR images** to reduce storage costs

---

## Conclusion

This deployment guide provides comprehensive instructions for containerizing and deploying the ResortsLite application to AWS ECS Fargate. Follow the security best practices and monitoring recommendations to ensure a production-ready deployment.

For questions or issues, refer to the troubleshooting section or consult the AWS documentation.

**Happy Deploying! 🚀**
