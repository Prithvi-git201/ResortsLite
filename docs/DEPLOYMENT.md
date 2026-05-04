# ResortsLite - AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development Setup](#local-development-setup)
4. [Building and Pushing Docker Images](#building-and-pushing-docker-images)
5. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
6. [ECS Task Definition Explained](#ecs-task-definition-explained)
7. [ECS Service Configuration](#ecs-service-configuration)
8. [Deployment to AWS ECS Fargate](#deployment-to-aws-ecs-fargate)
9. [Monitoring and Logging](#monitoring-and-logging)
10. [Troubleshooting](#troubleshooting)
11. [Scaling and Management](#scaling-and-management)
12. [Security Considerations](#security-considerations)

---

## Overview

ResortsLite is a Spring Boot 2.7.18 application built with Java 11 that provides booking management functionality. This guide covers containerization and deployment to AWS ECS Fargate.

**Application Details:**
- **Framework**: Spring Boot 2.7.18
- **Java Version**: 11
- **Build Tool**: Maven
- **Application Port**: 8080
- **Health Check Endpoint**: `/actuator/health`
- **Dependencies**: Redis (session management), AWS S3 (file storage), H2 Database (in-memory)

---

## Prerequisites

### Required Software
- **Docker**: Version 20.10 or higher
- **Docker Compose**: Version 2.0 or higher
- **AWS CLI**: Version 2.x
- **Java**: JDK 11 (for local development)
- **Maven**: Version 3.6+ (for local builds)

### AWS Account Requirements
- Active AWS account with appropriate permissions
- IAM user with permissions for:
  - ECS (create/update clusters, services, task definitions)
  - ECR (create repositories, push images)
  - EC2 (VPC, subnets, security groups)
  - IAM (create/manage roles)
  - CloudWatch Logs (create log groups)
  - Elastic Load Balancing (create ALB, target groups)

### External Services
- **Redis**: Managed Redis instance (AWS ElastiCache recommended)
- **AWS S3**: S3 bucket for file storage
- **VPC**: Configured VPC with at least 2 subnets in different availability zones

---

## Local Development Setup

### 1. Clone the Repository
```bash
cd /path/to/resortslite
```

### 2. Build the Application Locally
```bash
mvn clean package -DskipTests
```

### 3. Run with Docker Compose
```bash
# Set environment variables
export REDIS_HOST=your-redis-host
export REDIS_PORT=6379
export REDIS_PASSWORD=your-redis-password
export S3_BUCKET_NAME=your-s3-bucket
export AWS_REGION=us-east-1
export AWS_ACCESS_KEY_ID=your-access-key
export AWS_SECRET_ACCESS_KEY=your-secret-key

# Start the application
docker-compose up -d

# View logs
docker-compose logs -f

# Stop the application
docker-compose down
```

### 4. Access the Application
- **Application**: http://localhost:8080
- **Health Check**: http://localhost:8080/actuator/health
- **H2 Console**: http://localhost:8080/h2-console

---

## Building and Pushing Docker Images

### Option 1: Using build-push.sh (Linux/macOS)

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run the script
./scripts/build-push.sh
```

The script will prompt you for:
1. Image tag (default: latest)
2. Registry choice (AWS ECR or Docker Hub)
3. Registry-specific credentials

**For AWS ECR:**
- AWS Region
- AWS Account ID
- ECR Repository Name

**For Docker Hub:**
- Docker Hub Username
- Docker Hub Password/Token

### Option 2: Using build-push.bat (Windows)

```cmd
# Run the script
scripts\build-push.bat
```

Follow the same prompts as the Linux version.

### Manual Build and Push

**AWS ECR:**
```bash
# Authenticate with ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 123456789.dkr.ecr.us-east-1.amazonaws.com

# Create repository (if not exists)
aws ecr create-repository --repository-name resortslite --region us-east-1

# Build image
docker build -t resortslite:latest .

# Tag image
docker tag resortslite:latest 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Push image
docker push 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

**Docker Hub:**
```bash
# Login to Docker Hub
docker login

# Build image
docker build -t yourusername/resortslite:latest .

# Push image
docker push yourusername/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. Create IAM Roles

**ECS Task Execution Role** (required for Fargate):
```bash
# Create trust policy
cat > ecs-task-execution-trust-policy.json <<EOF
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

# Create role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document file://ecs-task-execution-trust-policy.json

# Attach AWS managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy
```

**ECS Task Role** (for application permissions):
```bash
# Create task role
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document file://ecs-task-execution-trust-policy.json

# Attach policies for S3 access
aws iam attach-role-policy \
  --role-name ecsTaskRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess
```

### 2. Configure VPC and Networking

**Create VPC (if needed):**
```bash
# Create VPC
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region us-east-1

# Create subnets in different AZs
aws ec2 create-subnet --vpc-id vpc-xxxxx --cidr-block 10.0.1.0/24 --availability-zone us-east-1a
aws ec2 create-subnet --vpc-id vpc-xxxxx --cidr-block 10.0.2.0/24 --availability-zone us-east-1b

# Create internet gateway
aws ec2 create-internet-gateway
aws ec2 attach-internet-gateway --vpc-id vpc-xxxxx --internet-gateway-id igw-xxxxx

# Create route table
aws ec2 create-route-table --vpc-id vpc-xxxxx
aws ec2 create-route --route-table-id rtb-xxxxx --destination-cidr-block 0.0.0.0/0 --gateway-id igw-xxxxx
```

**Create Security Group:**
```bash
# Create security group
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "Security group for ResortsLite ECS tasks" \
  --vpc-id vpc-xxxxx

# Allow inbound traffic on port 8080
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0

# Allow inbound traffic on port 80 (for ALB)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0
```

### 3. Setup Redis (ElastiCache)

```bash
# Create Redis cluster
aws elasticache create-cache-cluster \
  --cache-cluster-id resortslite-redis \
  --cache-node-type cache.t3.micro \
  --engine redis \
  --num-cache-nodes 1 \
  --security-group-ids sg-xxxxx

# Get Redis endpoint
aws elasticache describe-cache-clusters \
  --cache-cluster-id resortslite-redis \
  --show-cache-node-info
```

### 4. Setup S3 Bucket

```bash
# Create S3 bucket
aws s3 mb s3://resorts-lite-files --region us-east-1

# Enable versioning (optional)
aws s3api put-bucket-versioning \
  --bucket resorts-lite-files \
  --versioning-configuration Status=Enabled
```

### 5. Create CloudWatch Log Group

```bash
aws logs create-log-group --log-group-name /ecs/resortslite --region us-east-1
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) defines how your container runs on Fargate.

### Key Components

**Launch Type Configuration:**
```json
{
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc"
}
```
- `FARGATE`: Serverless compute engine
- `awsvpc`: Each task gets its own ENI with private IP

**CPU and Memory:**
```json
{
  "cpu": "512",
  "memory": "1024"
}
```
Valid Fargate combinations:
- CPU: 256 → Memory: 512, 1024, 2048 MB
- CPU: 512 → Memory: 1024, 2048, 3072, 4096 MB
- CPU: 1024 → Memory: 2048-8192 MB (1GB increments)
- CPU: 2048 → Memory: 4096-16384 MB (1GB increments)
- CPU: 4096 → Memory: 8192-30720 MB (1GB increments)

**Execution Role:**
```json
{
  "executionRoleArn": "arn:aws:iam::ACCOUNT_ID:role/ecsTaskExecutionRole"
}
```
Allows ECS to:
- Pull images from ECR
- Write logs to CloudWatch
- Retrieve secrets from Secrets Manager

**Task Role:**
```json
{
  "taskRoleArn": "arn:aws:iam::ACCOUNT_ID:role/ecsTaskRole"
}
```
Grants application permissions to:
- Access S3 buckets
- Call other AWS services

**Container Definition:**
```json
{
  "name": "resortslite",
  "image": "IMAGE_URI",
  "essential": true,
  "portMappings": [{"containerPort": 8080, "protocol": "tcp"}],
  "environment": [...],
  "logConfiguration": {...}
}
```

**Environment Variables:**
- `JAVA_OPTS`: JVM memory settings
- `SPRING_PROFILES_ACTIVE`: Spring profile
- `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`: Redis connection
- `S3_BUCKET_NAME`, `AWS_REGION`: S3 configuration
- Database connection strings

**Logging:**
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

The service definition (`ecs/service-definition.json`) manages task deployment and scaling.

### Key Components

**Launch Type:**
```json
{
  "launchType": "FARGATE",
  "platformVersion": "LATEST"
}
```

**Network Configuration:**
```json
{
  "networkConfiguration": {
    "awsvpcConfiguration": {
      "subnets": ["subnet-xxxxx", "subnet-yyyyy"],
      "securityGroups": ["sg-xxxxx"],
      "assignPublicIp": "ENABLED"
    }
  }
}
```
- **subnets**: At least 2 subnets in different AZs for high availability
- **securityGroups**: Controls inbound/outbound traffic
- **assignPublicIp**: ENABLED for internet access (or use NAT Gateway)

**Deployment Configuration:**
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
- **maximumPercent**: Maximum tasks during deployment (200% = double capacity)
- **minimumHealthyPercent**: Minimum healthy tasks (50% = half capacity)
- **deploymentCircuitBreaker**: Auto-rollback on failure

**Load Balancer Integration:**
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
- **targetGroupArn**: ALB target group (target-type must be `ip`)
- **healthCheckGracePeriodSeconds**: Time before health checks start (300s for JVM startup)

**Tags:**
```json
{
  "tags": [
    {"key": "Environment", "value": "production"},
    {"key": "Application", "value": "ResortsLite"}
  ]
}
```
⚠️ **CRITICAL**: Use `tags` parameter, NOT `serviceTags` (invalid and causes deployment failure)

---

## Deployment to AWS ECS Fargate

### Automated Deployment

**Linux/macOS:**
```bash
# Make script executable
chmod +x scripts/deploy-image.sh

# Run deployment
./scripts/deploy-image.sh
```

**Windows:**
```cmd
scripts\deploy-image.bat
```

### Deployment Prompts

The script will prompt for:

1. **AWS Configuration:**
   - AWS Region (e.g., us-east-1)
   - ECS Cluster Name

2. **Docker Image:**
   - Image URI (from ECR or Docker Hub)

3. **Network Configuration:**
   - VPC ID
   - Subnet IDs (comma-separated, at least 2)
   - Security Group ID

4. **External Services:**
   - Redis Host, Port, Password
   - S3 Bucket Name
   - Inventory Service URL

5. **Load Balancer:**
   - Whether to create ALB (y/n)

### Deployment Process

The script will:
1. ✅ Validate AWS credentials
2. ✅ Get AWS Account ID
3. ✅ Check/create ECS cluster
4. ✅ Create CloudWatch log group
5. ✅ Create ALB and Target Group (if requested)
6. ✅ Replace placeholders in task definition
7. ✅ Register task definition
8. ✅ Create or update ECS service
9. ✅ Wait for service stability
10. ✅ Display deployment status

### Manual Deployment

**1. Register Task Definition:**
```bash
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1
```

**2. Create Service:**
```bash
aws ecs create-service \
  --cli-input-json file://ecs/service-definition.json \
  --region us-east-1
```

**3. Update Service:**
```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:2 \
  --desired-count 2 \
  --region us-east-1
```

---

## Monitoring and Logging

### CloudWatch Logs

**View Logs:**
```bash
# Tail logs in real-time
aws logs tail /ecs/resortslite --follow --region us-east-1

# View specific time range
aws logs tail /ecs/resortslite \
  --since 1h \
  --format short \
  --region us-east-1

# Filter logs
aws logs tail /ecs/resortslite \
  --filter-pattern "ERROR" \
  --follow \
  --region us-east-1
```

**CloudWatch Console:**
1. Navigate to CloudWatch → Log groups
2. Select `/ecs/resortslite`
3. View log streams by task

### ECS Service Metrics

**View Service Status:**
```bash
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1
```

**View Running Tasks:**
```bash
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --region us-east-1
```

**View Task Details:**
```bash
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks task-id \
  --region us-east-1
```

### Application Health Checks

**Health Endpoint:**
```bash
# Direct task access (if public IP assigned)
curl http://TASK_PUBLIC_IP:8080/actuator/health

# Via Load Balancer
curl http://ALB_DNS_NAME/actuator/health
```

**Expected Response:**
```json
{
  "status": "UP",
  "components": {
    "diskSpace": {"status": "UP"},
    "redis": {"status": "UP"}
  }
}
```

### CloudWatch Alarms

**Create CPU Alarm:**
```bash
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

---

## Troubleshooting

### Common Issues

#### 1. Task Fails to Start

**Symptoms:**
- Tasks transition to STOPPED state immediately
- "ResourceInitializationError" in task events

**Solutions:**
```bash
# Check task stopped reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks task-id \
  --query 'tasks[0].stoppedReason'

# Common causes:
# - Invalid image URI → Verify ECR repository and image tag
# - Insufficient IAM permissions → Check executionRoleArn
# - Invalid CPU/memory combination → Use valid Fargate combinations
```

#### 2. Cannot Pull Image from ECR

**Symptoms:**
- "CannotPullContainerError" in task events

**Solutions:**
```bash
# Verify ECR repository exists
aws ecr describe-repositories --repository-names resortslite

# Check executionRoleArn has ECR permissions
aws iam get-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-name AmazonECSTaskExecutionRolePolicy

# Verify image exists
aws ecr describe-images \
  --repository-name resortslite \
  --image-ids imageTag=latest
```

#### 3. Health Check Failures

**Symptoms:**
- Tasks repeatedly fail health checks
- Service unable to reach steady state

**Solutions:**
```bash
# Check application logs
aws logs tail /ecs/resortslite --follow

# Verify health endpoint
curl http://TASK_IP:8080/actuator/health

# Common causes:
# - Application not starting (check logs for errors)
# - Redis connection failure (verify REDIS_HOST)
# - Insufficient healthCheckGracePeriodSeconds (increase to 300+)
```

#### 4. Network Connectivity Issues

**Symptoms:**
- Cannot access external services (Redis, S3)
- Tasks cannot pull images

**Solutions:**
```bash
# Verify security group allows outbound traffic
aws ec2 describe-security-groups --group-ids sg-xxxxx

# Check subnet route table has internet gateway
aws ec2 describe-route-tables --filters "Name=association.subnet-id,Values=subnet-xxxxx"

# Verify assignPublicIp is ENABLED or NAT Gateway configured
# For private subnets, use NAT Gateway for internet access
```

#### 5. Invalid CPU/Memory Configuration

**Symptoms:**
- "Invalid CPU or memory value specified" error

**Solutions:**
```json
// Use valid Fargate combinations:
{
  "cpu": "512",
  "memory": "1024"
}

// Valid combinations:
// CPU 256: memory 512, 1024, 2048
// CPU 512: memory 1024, 2048, 3072, 4096
// CPU 1024: memory 2048-8192 (1GB increments)
```

#### 6. Service Tags Error

**Symptoms:**
- "Unknown parameter in input: serviceTags" error

**Solutions:**
```json
// CORRECT - Use "tags" parameter:
{
  "tags": [
    {"key": "Environment", "value": "production"}
  ]
}

// INCORRECT - "serviceTags" is invalid:
{
  "serviceTags": [...]  // ❌ This will fail
}
```

### Debugging Commands

**View Task Logs:**
```bash
# Get task ID
TASK_ID=$(aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --query 'taskArns[0]' \
  --output text | cut -d'/' -f3)

# View logs
aws logs tail /ecs/resortslite --follow --filter-pattern $TASK_ID
```

**Check Service Events:**
```bash
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --query 'services[0].events[0:10]'
```

**Verify Task Definition:**
```bash
aws ecs describe-task-definition \
  --task-definition resortslite-task \
  --query 'taskDefinition'
```

---

## Scaling and Management

### Manual Scaling

**Update Desired Count:**
```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

**Create Auto Scaling Target:**
```bash
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10 \
  --region us-east-1
```

**Create Scaling Policy (CPU-based):**
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

**Using AWS CodeDeploy:**
1. Create CodeDeploy application
2. Configure deployment group with ECS service
3. Define deployment configuration (linear, canary, all-at-once)
4. Deploy new task definition revision

```bash
# Create CodeDeploy application
aws deploy create-application \
  --application-name resortslite-app \
  --compute-platform ECS

# Create deployment group
aws deploy create-deployment-group \
  --application-name resortslite-app \
  --deployment-group-name resortslite-dg \
  --service-role-arn arn:aws:iam::ACCOUNT_ID:role/CodeDeployServiceRole \
  --ecs-services clusterName=resortslite-cluster,serviceName=resortslite-service \
  --load-balancer-info targetGroupInfoList=[{name=resortslite-tg}] \
  --blue-green-deployment-configuration '{
    "terminateBlueInstancesOnDeploymentSuccess": {
      "action": "TERMINATE",
      "terminationWaitTimeInMinutes": 5
    },
    "deploymentReadyOption": {
      "actionOnTimeout": "CONTINUE_DEPLOYMENT"
    }
  }'
```

### Rolling Updates

**Update Task Definition:**
```bash
# Register new task definition revision
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json

# Update service with new revision
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:3 \
  --force-new-deployment
```

---

## Security Considerations

### 1. IAM Roles and Permissions

**Principle of Least Privilege:**
- Grant only necessary permissions to task role
- Use separate roles for execution and task
- Regularly audit IAM policies

**Example Task Role Policy:**
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
      "Resource": "arn:aws:s3:::resorts-lite-files/*"
    }
  ]
}
```

### 2. Secrets Management

**Use AWS Secrets Manager:**
```bash
# Store Redis password
aws secretsmanager create-secret \
  --name resortslite/redis-password \
  --secret-string "your-redis-password"

# Reference in task definition
{
  "secrets": [
    {
      "name": "REDIS_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:region:account:secret:resortslite/redis-password"
    }
  ]
}
```

### 3. Network Security

**Security Group Best Practices:**
- Restrict inbound traffic to necessary ports only
- Use security group rules instead of CIDR blocks
- Enable VPC Flow Logs for traffic monitoring

**Example Security Group Rules:**
```bash
# Allow traffic only from ALB security group
aws ec2 authorize-security-group-ingress \
  --group-id sg-task \
  --protocol tcp \
  --port 8080 \
  --source-group sg-alb
```

### 4. Container Security

**Image Scanning:**
```bash
# Enable ECR image scanning
aws ecr put-image-scanning-configuration \
  --repository-name resortslite \
  --image-scanning-configuration scanOnPush=true

# View scan results
aws ecr describe-image-scan-findings \
  --repository-name resortslite \
  --image-id imageTag=latest
```

**Non-Root User:**
The Dockerfile creates and uses a non-root user (`appuser`) for running the application.

### 5. Encryption

**Enable Encryption:**
- **ECS Task Logs**: CloudWatch Logs encryption with KMS
- **S3 Bucket**: Server-side encryption (SSE-S3 or SSE-KMS)
- **Redis**: ElastiCache encryption at rest and in transit

```bash
# Enable S3 bucket encryption
aws s3api put-bucket-encryption \
  --bucket resorts-lite-files \
  --server-side-encryption-configuration '{
    "Rules": [{
      "ApplyServerSideEncryptionByDefault": {
        "SSEAlgorithm": "AES256"
      }
    }]
  }'
```

### 6. Compliance and Auditing

**Enable AWS CloudTrail:**
```bash
aws cloudtrail create-trail \
  --name resortslite-trail \
  --s3-bucket-name cloudtrail-logs-bucket

aws cloudtrail start-logging --name resortslite-trail
```

**Enable VPC Flow Logs:**
```bash
aws ec2 create-flow-logs \
  --resource-type VPC \
  --resource-ids vpc-xxxxx \
  --traffic-type ALL \
  --log-destination-type cloud-watch-logs \
  --log-group-name /aws/vpc/flowlogs
```

---

## Technology-Specific Notes

### Spring Boot Configuration

**Profiles:**
- `docker`: Used in containerized environments
- Configure profile-specific properties in `application-docker.properties`

**JVM Tuning:**
```bash
# Recommended JVM options for containers
JAVA_OPTS="-Xmx512m -Xms256m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

**Actuator Endpoints:**
- `/actuator/health`: Health check (used by ALB)
- `/actuator/info`: Application information
- `/actuator/metrics`: Application metrics

### Redis Session Management

**Configuration:**
```properties
spring.session.store-type=redis
spring.redis.host=${REDIS_HOST}
spring.redis.port=${REDIS_PORT}
spring.redis.password=${REDIS_PASSWORD}
```

**Connection Pooling:**
- Uses Lettuce client (default in Spring Boot)
- Configure connection pool size based on load

### AWS S3 Integration

**SDK Configuration:**
```java
// Uses AWS SDK v2
// Credentials from IAM task role (no hardcoded keys)
S3Client s3Client = S3Client.builder()
    .region(Region.of(System.getenv("AWS_REGION")))
    .build();
```

### H2 Database

**In-Memory Database:**
- Used for development/testing
- Data is lost when container restarts
- For production, consider RDS (PostgreSQL, MySQL)

---

## Additional Resources

### AWS Documentation
- [ECS Fargate Documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [ECS Task Definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [ECS Service Definition](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service_definition_parameters.html)

### Spring Boot Resources
- [Spring Boot Docker Guide](https://spring.io/guides/gs/spring-boot-docker/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Spring Session Redis](https://docs.spring.io/spring-session/docs/current/reference/html5/#httpsession-redis)

### Best Practices
- [AWS ECS Best Practices](https://docs.aws.amazon.com/AmazonECS/latest/bestpracticesguide/intro.html)
- [Docker Best Practices](https://docs.docker.com/develop/dev-best-practices/)
- [Java Container Best Practices](https://developers.redhat.com/blog/2017/03/14/java-inside-docker)

---

## Support and Maintenance

### Monitoring Checklist
- [ ] CloudWatch alarms configured
- [ ] Log retention policies set
- [ ] Auto-scaling policies tested
- [ ] Backup strategy for S3 data
- [ ] Disaster recovery plan documented

### Regular Maintenance
- Update base images regularly for security patches
- Review and rotate IAM credentials
- Monitor CloudWatch costs
- Review and optimize resource allocation
- Update Spring Boot and dependencies

### Contact Information
For issues or questions:
- AWS Support: https://console.aws.amazon.com/support/
- Spring Boot Issues: https://github.com/spring-projects/spring-boot/issues

---

**Document Version**: 1.0  
**Last Updated**: 2024  
**Target Platform**: AWS ECS Fargate  
**Application**: ResortsLite v1.0.0
