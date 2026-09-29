# ResortsLite — AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Project Structure](#project-structure)
4. [Local Development with Docker Compose](#local-development-with-docker-compose)
5. [Build and Push Docker Image](#build-and-push-docker-image)
6. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
7. [ECS Task Definition Explained](#ecs-task-definition-explained)
8. [ECS Service Configuration](#ecs-service-configuration)
9. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
10. [ECS-Specific Troubleshooting](#ecs-specific-troubleshooting)
11. [ECS Fargate Scaling and Management](#ecs-fargate-scaling-and-management)
12. [Configuration Management](#configuration-management)
13. [Security Considerations](#security-considerations)
14. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Runtime Base Image**: `amazoncorretto:8`  
**Target Platform**: AWS ECS Fargate  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a Spring Boot REST API for resort booking management. It uses:
- **Amazon ElastiCache (Memcached)** for distributed booking cache
- **AWS Secrets Manager** for JWT signing secrets
- **AWS SSM Parameter Store** for Memcached endpoint configuration
- **Amazon EFS** for EFS-backed report and backup file storage
- **ALB sticky sessions** as a transitional session-affinity strategy

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker | 20.10+ | Container runtime |
| Docker Compose | 2.x | Local orchestration |
| Java JDK | 8+ | Local build (optional) |
| Maven | 3.9+ | Local build (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS resource management |
| Docker | 20.10+ | Image build and push |
| Python 3 | 3.6+ | Deployment script helper |

---

## Project Structure

```
M-mono-Resortlite-CMP/
├── Dockerfile                    # Multi-stage build (builder + runtime)
├── docker-compose.yml            # Local development (app only)
├── .dockerignore                 # Excludes target/, wrapper files, etc.
├── pom.xml                       # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   ├── MemcachedConfig.java
│       │   └── JwtUtil.java
│       └── resources/
│           └── application.properties
├── ecs/
│   ├── task-definition.json      # ECS Fargate task definition
│   └── service-definition.json   # ECS Fargate service definition
├── scripts/
│   ├── build-push.sh             # Linux/macOS build & push
│   ├── build-push.bat            # Windows build & push
│   ├── deploy-image.sh           # Linux/macOS ECS deployment
│   └── deploy-image.bat          # Windows ECS deployment
└── docs/
    └── DEPLOYMENT.md             # This file
```

---

## Local Development with Docker Compose

### 1. Configure Environment Variables

Create a `.env` file in the project root (never commit this file):

```bash
# JWT secret (use a strong random value in production)
JWT_SECRET=my-super-secret-jwt-key-at-least-32-chars

# Memcached (use localhost for local dev without ElastiCache)
MEMCACHED_ENDPOINT=localhost:11211
MEMCACHED_EXPIRY_SECS=3600

# ALB stickiness (informational for local dev)
ALB_STICKINESS_ENABLED=true
ALB_COOKIE_DURATION_SECS=86400

# EFS paths (local directories for dev)
REPORT_BASE_PATH=/mnt/efs/reports
BACKUP_PATH=/mnt/efs/backups
```

### 2. Build and Start the Application

```bash
# Build and start
docker compose up --build

# Start in background
docker compose up --build -d

# View logs
docker compose logs -f resortslite

# Stop
docker compose down
```

### 3. Verify the Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=Suite&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=Suite"
```

---

## Build and Push Docker Image

### Linux / macOS

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run from project root
./scripts/build-push.sh
```

### Windows

```cmd
scripts\build-push.bat
```

### Script Prompts

The script will ask:
1. **Image tag** — defaults to `latest`
2. **Registry type** — `1` for AWS ECR, `2` for Docker Hub
3. **Registry details** — region/repo for ECR, or username/password for Docker Hub

The script automatically:
- Sanitizes the image name (lowercase, hyphens)
- Logs in to the selected registry
- Creates the ECR repository if it doesn't exist
- Builds and pushes the image

### Manual Build

```bash
# Build
docker build -t resortslite:latest .

# Tag for ECR
docker tag resortslite:latest 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Push
docker push 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. AWS CLI Configuration

```bash
aws configure
# AWS Access Key ID: <your-key>
# AWS Secret Access Key: <your-secret>
# Default region: us-east-1
# Default output format: json
```

### 2. VPC and Networking

Ensure you have:
- A VPC with at least **2 subnets** in different Availability Zones
- A **Security Group** that allows:
  - Inbound TCP 8080 from ALB security group (or 0.0.0.0/0 for testing)
  - Outbound all traffic (for ECR image pull, CloudWatch logs, SSM)

```bash
# List VPCs
aws ec2 describe-vpcs --query "Vpcs[*].{VpcId:VpcId,CIDR:CidrBlock}" --output table

# List subnets
aws ec2 describe-subnets --query "Subnets[*].{SubnetId:SubnetId,AZ:AvailabilityZone,CIDR:CidrBlock}" --output table
```

### 3. IAM Roles

#### ECS Task Execution Role (`ecsTaskExecutionRole`)
Required for ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role
aws iam create-role \
  --role-name ecsTaskExecutionRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'

# Attach managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

# Add Secrets Manager and SSM access
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite

aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess
```

#### ECS Task Role (`ecsTaskRole`)
Permissions for the application itself (e.g., S3, DynamoDB access).

```bash
aws iam create-role \
  --role-name ecsTaskRole \
  --assume-role-policy-document '{
    "Version": "2012-10-17",
    "Statement": [{
      "Effect": "Allow",
      "Principal": {"Service": "ecs-tasks.amazonaws.com"},
      "Action": "sts:AssumeRole"
    }]
  }'
```

### 4. AWS Secrets Manager — JWT Secret

```bash
aws secretsmanager create-secret \
  --name resortslite/jwt-secret \
  --secret-string "$(openssl rand -base64 48)" \
  --region us-east-1
```

### 5. AWS SSM Parameter Store — Memcached Endpoint

```bash
# After creating your ElastiCache Memcached cluster:
aws ssm put-parameter \
  --name /resortslite/prod/memcached/endpoint \
  --value "my-cluster.abc123.cfg.use1.cache.amazonaws.com:11211" \
  --type SecureString \
  --region us-east-1

aws ssm put-parameter \
  --name /resortslite/prod/memcached/expiry-secs \
  --value "3600" \
  --type String \
  --region us-east-1
```

### 6. CloudWatch Log Group

```bash
aws logs create-log-group \
  --log-group-name /ecs/resortslite \
  --region us-east-1

# Set retention (optional)
aws logs put-retention-policy \
  --log-group-name /ecs/resortslite \
  --retention-in-days 30 \
  --region us-east-1
```

### 7. Amazon EFS (for Reports and Backups)

```bash
# Create EFS file system
EFS_ID=$(aws efs create-file-system \
  --performance-mode generalPurpose \
  --throughput-mode bursting \
  --region us-east-1 \
  --query "FileSystemId" --output text)

echo "EFS File System ID: $EFS_ID"

# Create mount targets in each subnet
aws efs create-mount-target \
  --file-system-id $EFS_ID \
  --subnet-id subnet-xxxxxxxx \
  --security-groups sg-xxxxxxxx \
  --region us-east-1

# Create access points
aws efs create-access-point \
  --file-system-id $EFS_ID \
  --posix-user "Uid=1000,Gid=1000" \
  --root-directory "Path=/reports,CreationInfo={OwnerUid=1000,OwnerGid=1000,Permissions=755}" \
  --region us-east-1
```

Update `ecs/task-definition.json` with the actual `EFS_FILE_SYSTEM_ID`.

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) configures:

| Field | Value | Notes |
|-------|-------|-------|
| `family` | `resortslite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | ECR pull + CloudWatch logs |
| `taskRoleArn` | `ecsTaskRole` | Application permissions |

### Valid Fargate CPU/Memory Combinations

| CPU | Memory Options |
|-----|---------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024, 2048, 3072, 4096 MB** ← Used |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

### Container Definition Highlights

- **Secrets**: `JWT_SECRET` from Secrets Manager, `MEMCACHED_ENDPOINT` from SSM
- **Logging**: CloudWatch Logs via `awslogs` driver → `/ecs/resortslite`
- **EFS Mounts**: `/mnt/efs/reports` and `/mnt/efs/backups`
- **Stop Timeout**: 30 seconds for graceful JVM shutdown

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) configures:

| Field | Value | Notes |
|-------|-------|-------|
| `serviceName` | `resortslite-service` | ECS service name |
| `launchType` | `FARGATE` | Serverless containers |
| `desiredCount` | `2` | Two replicas for HA |
| `networkMode` | `awsvpc` | Each task gets its own ENI |
| `assignPublicIp` | `ENABLED` | Required for ECR access without NAT |
| `maximumPercent` | `200` | Rolling deploy: up to 4 tasks |
| `minimumHealthyPercent` | `50` | Rolling deploy: at least 1 task |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and Push the Image

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
# Select: 1 (AWS ECR)
# Region: us-east-1
# Repo: resortslite
# Tag: 1.0.0
```

### Step 2: Update Task Definition Placeholders

Before running the deploy script, verify `ecs/task-definition.json` has the correct `EFS_FILE_SYSTEM_ID` if using EFS volumes.

### Step 3: Run the Deployment Script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

The script will prompt for:
- AWS Region
- ECS Cluster name
- ECR Image URI
- VPC ID
- Subnet IDs (comma-separated)
- Security Group ID
- Whether to create an ALB

### Step 4: Verify the Deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1

# List running tasks
aws ecs list-tasks \
  --cluster resortslite-cluster \
  --service-name resortslite-service \
  --region us-east-1

# View application logs
aws logs tail /ecs/resortslite --follow --region us-east-1
```

### Step 5: Test the Application

```bash
# If using ALB:
curl http://<ALB_DNS>/actuator/health

# If using direct task IP (for testing):
TASK_ARN=$(aws ecs list-tasks --cluster resortslite-cluster --service-name resortslite-service --query "taskArns[0]" --output text)
TASK_IP=$(aws ecs describe-tasks --cluster resortslite-cluster --tasks $TASK_ARN --query "tasks[0].attachments[0].details[?name=='privateIPv4Address'].value" --output text)
curl http://$TASK_IP:8080/actuator/health
```

---

## ECS-Specific Troubleshooting

### Task Fails to Start

```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <TASK_ARN> \
  --query "tasks[0].{Status:lastStatus,StopCode:stopCode,StopReason:stoppedReason}" \
  --output table

# Check container exit code
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <TASK_ARN> \
  --query "tasks[0].containers[0].{ExitCode:exitCode,Reason:reason}" \
  --output table
```

### Common Error: `CannotPullContainerError`
- **Cause**: ECS cannot pull image from ECR
- **Fix**: Ensure `ecsTaskExecutionRole` has `AmazonECSTaskExecutionRolePolicy`
- **Fix**: Ensure subnets have internet access (public subnet with `assignPublicIp: ENABLED` or private subnet with NAT Gateway)

### Common Error: `ResourceInitializationError`
- **Cause**: Secrets Manager or SSM parameter not found
- **Fix**: Verify secret/parameter ARNs in task definition match actual resources
- **Fix**: Ensure `ecsTaskExecutionRole` has `secretsmanager:GetSecretValue` and `ssm:GetParameters` permissions

### Common Error: Invalid CPU/Memory Combination
- **Cause**: Using incompatible cpu/memory values
- **Fix**: Use valid combinations (e.g., cpu: "512", memory: "1024")

### JVM OutOfMemoryError in Container
- **Cause**: JVM heap exceeds container memory limit
- **Fix**: Ensure `JAVA_OPTS` includes `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`
- **Fix**: Increase task memory (e.g., from 1024 to 2048 MB)

### Memcached Connection Refused
- **Cause**: `MEMCACHED_ENDPOINT` not set or ElastiCache cluster not accessible
- **Fix**: Verify SSM parameter `/resortslite/prod/memcached/endpoint` exists
- **Fix**: Ensure ECS task security group allows outbound TCP 11211 to ElastiCache security group

### Application Logs Not Appearing
```bash
# Verify log group exists
aws logs describe-log-groups --log-group-name-prefix /ecs/resortslite

# Check log streams
aws logs describe-log-streams \
  --log-group-name /ecs/resortslite \
  --order-by LastEventTime \
  --descending
```

---

## ECS Fargate Scaling and Management

### Manual Scaling

```bash
# Scale up to 4 replicas
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 4 \
  --region us-east-1

# Scale down to 1 replica
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 1 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10

# Create CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortslite-cluster/resortslite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name resortslite-cpu-scaling \
  --policy-type TargetTrackingScaling \
  --target-tracking-scaling-policy-configuration '{
    "TargetValue": 70.0,
    "PredefinedMetricSpecification": {
      "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
    },
    "ScaleInCooldown": 300,
    "ScaleOutCooldown": 60
  }'
```

### Blue/Green Deployment with CodeDeploy

For zero-downtime deployments, configure AWS CodeDeploy with ECS:

1. Create a CodeDeploy application and deployment group targeting the ECS service
2. Configure two ALB listener rules (production and test traffic)
3. Use `aws deploy create-deployment` to trigger blue/green deployments

### Force New Deployment (Rolling Update)

```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Source | Description |
|----------|--------|-------------|
| `SPRING_PROFILES_ACTIVE` | Task Definition | Spring profile (`docker`) |
| `SERVER_PORT` | Task Definition | Application port (8080) |
| `JAVA_OPTS` | Task Definition | JVM flags |
| `JWT_SECRET` | Secrets Manager | JWT signing key |
| `MEMCACHED_ENDPOINT` | SSM Parameter Store | ElastiCache endpoint |
| `MEMCACHED_EXPIRY_SECS` | SSM Parameter Store | Cache TTL in seconds |
| `ALB_STICKINESS_ENABLED` | Task Definition | ALB sticky sessions flag |
| `ALB_COOKIE_DURATION_SECS` | Task Definition | Sticky session duration |
| `REPORT_BASE_PATH` | Task Definition | EFS reports mount path |
| `BACKUP_PATH` | Task Definition | EFS backups mount path |
| `TZ` | Task Definition | Timezone (UTC) |

### Updating Secrets

```bash
# Rotate JWT secret
aws secretsmanager put-secret-value \
  --secret-id resortslite/jwt-secret \
  --secret-string "$(openssl rand -base64 48)" \
  --region us-east-1

# Force new deployment to pick up new secret
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Security Considerations

1. **Non-root Container**: The application runs as `appuser` (non-root) inside the container
2. **No Hardcoded Secrets**: All secrets are injected via Secrets Manager and SSM Parameter Store
3. **Network Isolation**: Use private subnets with NAT Gateway for production workloads
4. **Security Groups**: Restrict inbound traffic to ALB security group only
5. **EFS Encryption**: EFS volumes use transit encryption (`transitEncryption: ENABLED`)
6. **Image Scanning**: Enable ECR image scanning on push:
   ```bash
   aws ecr put-image-scanning-configuration \
     --repository-name resortslite \
     --image-scanning-configuration scanOnPush=true
   ```
7. **IAM Least Privilege**: Scope `ecsTaskRole` permissions to only required resources
8. **Log Retention**: Set CloudWatch log retention to avoid unbounded storage costs
9. **VPC Endpoints**: Use VPC endpoints for ECR, Secrets Manager, and SSM to avoid internet traffic

---

## Java-Specific Notes

### JVM Container Awareness

The Dockerfile sets:
```
-XX:+UseContainerSupport        # JVM respects container CPU/memory limits
-XX:MaxRAMPercentage=75.0       # Use 75% of container memory for heap
-XX:+ExitOnOutOfMemoryError     # Crash fast on OOM (ECS will restart)
-Djava.security.egd=file:/dev/./urandom  # Faster SecureRandom on Linux
```

### Spring Boot Actuator

Health endpoint is exposed at `/actuator/health`. The ALB health check uses this endpoint.

```bash
# Check health
curl http://localhost:8080/actuator/health

# Expected response
{"status":"UP"}
```

### Graceful Shutdown

Spring Boot 2.7.x supports graceful shutdown. The container uses `STOPSIGNAL SIGTERM` and ECS sends SIGTERM before SIGKILL (after `stopTimeout: 30` seconds).

To enable graceful shutdown in Spring Boot:
```properties
server.shutdown=graceful
spring.lifecycle.timeout-per-shutdown-phase=20s
```

### H2 In-Memory Database

The application uses H2 in-memory database for development. For production:
- Replace with Amazon RDS (PostgreSQL/MySQL)
- Update `spring.datasource.*` properties
- Add the appropriate JDBC driver dependency to `pom.xml`

### Log4j Security Note

The `pom.xml` includes `log4j-core:2.14.1` which has **CVE-2021-44228 (Log4Shell)**. This should be upgraded to `2.17.2+` before production deployment:

```xml
<dependency>
    <groupId>org.apache.logging.log4j</groupId>
    <artifactId>log4j-core</artifactId>
    <version>2.17.2</version>
</dependency>
```

### Commons Collections Security Note

The `pom.xml` includes `commons-collections:3.2.1` which has **CVE-2015-6420 (RCE)**. Upgrade to `commons-collections4:4.4`:

```xml
<dependency>
    <groupId>org.apache.commons</groupId>
    <artifactId>commons-collections4</artifactId>
    <version>4.4</version>
</dependency>
```
