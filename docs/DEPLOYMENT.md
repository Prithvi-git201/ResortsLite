# ResortsLite — AWS ECS Fargate Deployment Guide

## Table of Contents
1. [Overview](#overview)
2. [Prerequisites](#prerequisites)
3. [Local Development with Docker Compose](#local-development-with-docker-compose)
4. [Build and Push Docker Image](#build-and-push-docker-image)
5. [AWS ECS Fargate Prerequisites](#aws-ecs-fargate-prerequisites)
6. [ECS Task Definition Explained](#ecs-task-definition-explained)
7. [ECS Service Configuration](#ecs-service-configuration)
8. [ECS Fargate Deployment Walkthrough](#ecs-fargate-deployment-walkthrough)
9. [Configuration Management](#configuration-management)
10. [Security Considerations](#security-considerations)
11. [Monitoring and Observability](#monitoring-and-observability)
12. [Scaling and Management](#scaling-and-management)
13. [Troubleshooting](#troubleshooting)
14. [Technology-Specific Notes](#technology-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.18  
**Java Version**: 8 (eclipse-temurin)  
**Build Tool**: Maven  
**Package Type**: JAR (executable fat JAR via spring-boot-maven-plugin)  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`  
**Target Platform**: AWS ECS Fargate  

ResortsLite is a legacy resort booking application modernised for cloud-native deployment on AWS ECS Fargate. It uses stateless JWT authentication, Amazon ElastiCache Memcached for distributed caching, and Amazon EFS for persistent file storage.

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Docker Desktop | 24.x+ | Build and run containers |
| Docker Compose | 2.x+ | Local multi-container orchestration |
| Java JDK | 8+ | Local development (optional) |
| Maven | 3.9.x | Local builds (optional) |

### AWS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| AWS CLI | 2.x | AWS resource management |
| Docker | 24.x+ | Image build and push |
| Python 3 | 3.8+ | JSON manipulation in deploy script |

---

## Local Development with Docker Compose

### 1. Clone and navigate to the project
```bash
cd /path/to/Sanity
```

### 2. Build and start the application
```bash
docker compose up --build
```

### 3. Verify the application is running
```bash
# Health check
curl http://localhost:8080/actuator/health

# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=SUITE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=SUITE"
```

### 4. View logs
```bash
docker compose logs -f resortslite
```

### 5. Stop the application
```bash
docker compose down
```

### Environment Variable Overrides (local)
Create a `.env` file in the project root to override defaults:
```env
JWT_SECRET=my-local-dev-secret-at-least-32-chars
MEMCACHED_ENDPOINT=localhost:11211
PAYMENT_API_URL=http://localhost:9090/payments/charge
REPORT_BASE_PATH=/tmp/reports/
BACKUP_PATH=/tmp/backups/nightly/
```

---

## Build and Push Docker Image

### Linux / macOS
```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

### Windows
```cmd
scripts\build-push.bat
```

The script will prompt you to:
1. Enter an image tag (defaults to `latest`)
2. Select a registry: **AWS ECR** or **Docker Hub**
3. Provide registry credentials / details

**AWS ECR flow:**
- Prompts for AWS Region and Account ID (auto-detected if blank)
- Authenticates with ECR
- Auto-creates the ECR repository if it does not exist
- Builds and pushes the image

**Docker Hub flow:**
- Prompts for username, password/token, and namespace
- Authenticates and pushes the image

---

## AWS ECS Fargate Prerequisites

### 1. AWS CLI Configuration
```bash
aws configure
# Enter: Access Key ID, Secret Access Key, Region, Output format
```

### 2. VPC and Networking
- A VPC with at least **two public or private subnets** in different Availability Zones
- A **Security Group** that allows:
  - Inbound TCP 8080 from the ALB security group (or 0.0.0.0/0 for testing)
  - Outbound all traffic (for ECR image pull, CloudWatch logs, external services)

```bash
# Example: Create a security group
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "ResortsLite ECS tasks" \
  --vpc-id vpc-xxxxxxxxx

aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0
```

### 3. IAM Roles

#### ECS Task Execution Role (`ecsTaskExecutionRole`)
Required for ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the role (if it doesn't exist)
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

# Attach the managed policy
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy

# Add Secrets Manager access (for JWT_SECRET)
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite
```

#### ECS Task Role (`ecsTaskRole`)
Grants the application container permissions to access AWS services.

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

# Attach policies for SSM Parameter Store, EFS, CloudWatch
aws iam attach-role-policy \
  --role-name ecsTaskRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess

aws iam attach-role-policy \
  --role-name ecsTaskRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonElasticFileSystemClientReadWriteAccess
```

### 4. ECR Repository
```bash
aws ecr create-repository \
  --repository-name resortslite \
  --region us-east-1
```

### 5. CloudWatch Log Group
```bash
aws logs create-log-group \
  --log-group-name /ecs/resortslite \
  --region us-east-1
```

### 6. AWS Secrets Manager (JWT Secret)
```bash
aws secretsmanager create-secret \
  --name resortslite/jwt-secret \
  --secret-string "your-production-jwt-secret-at-least-32-chars" \
  --region us-east-1
```

### 7. Amazon EFS (for report/backup volumes)
```bash
# Create EFS file system
aws efs create-file-system \
  --performance-mode generalPurpose \
  --throughput-mode bursting \
  --tags Key=Name,Value=resortslite-efs \
  --region us-east-1

# Create mount targets in each subnet
aws efs create-mount-target \
  --file-system-id fs-xxxxxxxxx \
  --subnet-id subnet-xxxxxxxxx \
  --security-groups sg-xxxxxxxxx \
  --region us-east-1
```

---

## ECS Task Definition Explained

File: `ecs/task-definition.json`

| Field | Value | Description |
|-------|-------|-------------|
| `family` | `resortslite-task` | Task definition family name |
| `requiresCompatibilities` | `["FARGATE"]` | Fargate launch type |
| `networkMode` | `awsvpc` | Required for Fargate; each task gets its own ENI |
| `cpu` | `"512"` | 0.5 vCPU |
| `memory` | `"1024"` | 1 GB RAM |
| `executionRoleArn` | `ecsTaskExecutionRole` | Allows ECR pull and CloudWatch logging |
| `taskRoleArn` | `ecsTaskRole` | Application-level AWS permissions |

### Valid Fargate CPU/Memory Combinations
| CPU | Valid Memory Options |
|-----|---------------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024**, 2048, 3072, 4096 MB |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

### Container Definition Key Fields
- **`image`**: Replaced by `{{IMAGE_URI}}` placeholder at deploy time
- **`portMappings`**: Container port 8080 (no host port in Fargate)
- **`secrets`**: `JWT_SECRET` sourced from AWS Secrets Manager
- **`environment`**: Non-sensitive configuration values
- **`logConfiguration`**: CloudWatch Logs via `awslogs` driver
- **`mountPoints`**: EFS volumes for `/mnt/efs/reports` and `/mnt/efs/backups/nightly`

---

## ECS Service Configuration

File: `ecs/service-definition.json`

| Field | Value | Description |
|-------|-------|-------------|
| `serviceName` | `resortslite-service` | ECS service name |
| `launchType` | `FARGATE` | Serverless compute |
| `desiredCount` | `2` | Two tasks for high availability |
| `networkMode` | `awsvpc` | Each task gets its own ENI |
| `assignPublicIp` | `ENABLED` | Required for public subnet tasks to reach ECR |
| `maximumPercent` | `200` | Up to 4 tasks during rolling deployment |
| `minimumHealthyPercent` | `50` | At least 1 task always running |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and push the image
```bash
./scripts/build-push.sh
# Select AWS ECR, enter region and account ID
# Note the full image URI output (e.g. 123456789.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest)
```

### Step 2: Run the deployment script
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
- EFS File System ID
- Whether to create an Application Load Balancer

### Step 3: Verify the deployment
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

### Step 4: Test the application
```bash
# If using ALB:
curl http://<ALB_DNS>/actuator/health

# If using task public IP (for testing only):
TASK_ARN=$(aws ecs list-tasks --cluster resortslite-cluster --service-name resortslite-service --query "taskArns[0]" --output text --region us-east-1)
TASK_IP=$(aws ecs describe-tasks --cluster resortslite-cluster --tasks $TASK_ARN --region us-east-1 --query "tasks[0].attachments[0].details[?name=='privateIPv4Address'].value" --output text)
curl http://$TASK_IP:8080/actuator/health
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Source | Description |
|----------|--------|-------------|
| `SPRING_PROFILES_ACTIVE` | Task definition | Spring profile (`docker`) |
| `SERVER_PORT` | Task definition | Application port (8080) |
| `JWT_SECRET` | AWS Secrets Manager | JWT signing secret (min 32 chars) |
| `MEMCACHED_ENDPOINT` | Task definition / SSM | ElastiCache endpoint `host:port` |
| `PAYMENT_API_URL` | Task definition / SSM | Payment service URL |
| `REPORT_BASE_PATH` | Task definition | EFS mount path for reports |
| `BACKUP_PATH` | Task definition | EFS mount path for backups |
| `JAVA_OPTS` | Task definition | JVM tuning flags |
| `TZ` | Task definition | Timezone (UTC) |

### Updating Configuration
To update environment variables without rebuilding the image:
```bash
# Update task definition with new environment values
# Edit ecs/task-definition.json, then re-run deploy-image.sh
# ECS will perform a rolling update automatically
```

### AWS SSM Parameter Store (recommended for non-secret config)
```bash
aws ssm put-parameter \
  --name /resortsLite/cache/memcachedEndpoint \
  --value "resortsLite-cache.abc123.cfg.use1.cache.amazonaws.com:11211" \
  --type String \
  --region us-east-1
```

---

## Security Considerations

### Container Security
- Application runs as non-root user (`appuser`) inside the container
- No shell tools (curl, wget) installed in the runtime image
- Minimal `eclipse-temurin:8-jre` base image reduces attack surface

### Network Security
- Use private subnets with NAT Gateway for production workloads
- Restrict Security Group inbound rules to ALB security group only
- Enable VPC Flow Logs for network traffic auditing

### Secrets Management
- `JWT_SECRET` is stored in AWS Secrets Manager and injected at runtime
- Never commit secrets to source control
- Rotate secrets regularly using Secrets Manager rotation

### Image Security
- Scan ECR images with Amazon Inspector or Trivy
- Enable ECR image scanning on push:
```bash
aws ecr put-image-scanning-configuration \
  --repository-name resortslite \
  --image-scanning-configuration scanOnPush=true \
  --region us-east-1
```

### Known Security Issues in Source Code
The following violations exist in the source code and should be addressed:
- **CVE-2021-44228 (Log4Shell)**: `log4j-core:2.14.1` — upgrade to 2.17.2+
- **CVE-2015-6420**: `commons-collections:3.2.1` — upgrade to 3.2.2+
- **SQL Injection**: `BookingService` uses string concatenation in SQL queries — use parameterised queries
- **Weak Hashing**: MD5 used for confirmation codes — use SHA-256 or bcrypt
- **Hardcoded Credentials**: `DB_HOST`, `DB_USER`, `DB_PASS` in `BookingService` — move to Secrets Manager

---

## Monitoring and Observability

### CloudWatch Logs
```bash
# Stream application logs
aws logs tail /ecs/resortslite --follow --region us-east-1

# Search for errors
aws logs filter-log-events \
  --log-group-name /ecs/resortslite \
  --filter-pattern "ERROR" \
  --region us-east-1
```

### Spring Boot Actuator Endpoints
| Endpoint | URL | Description |
|----------|-----|-------------|
| Health | `/actuator/health` | Application health status |
| Info | `/actuator/info` | Application information |

### CloudWatch Metrics
ECS Fargate automatically publishes metrics to CloudWatch:
- `CPUUtilization` — CPU usage per task
- `MemoryUtilization` — Memory usage per task

```bash
# Create a CPU alarm
aws cloudwatch put-metric-alarm \
  --alarm-name resortslite-high-cpu \
  --metric-name CPUUtilization \
  --namespace AWS/ECS \
  --dimensions Name=ClusterName,Value=resortslite-cluster Name=ServiceName,Value=resortslite-service \
  --statistic Average \
  --period 300 \
  --threshold 80 \
  --comparison-operator GreaterThanThreshold \
  --evaluation-periods 2 \
  --alarm-actions arn:aws:sns:us-east-1:ACCOUNT_ID:alerts \
  --region us-east-1
```

---

## Scaling and Management

### Manual Scaling
```bash
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --desired-count 4 \
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
  --max-capacity 10 \
  --region us-east-1

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
  }' \
  --region us-east-1
```

### Blue/Green Deployments
For zero-downtime deployments, use AWS CodeDeploy with ECS:
1. Enable CodeDeploy in the ECS service definition
2. Create a CodeDeploy application and deployment group
3. Use `appspec.yaml` to define the deployment lifecycle

### Rolling Updates
The current configuration supports rolling updates:
- `maximumPercent: 200` — allows double capacity during deployment
- `minimumHealthyPercent: 50` — keeps at least 1 task running

```bash
# Force a new deployment (e.g., after updating task definition)
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Troubleshooting

### Task Fails to Start
```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].{Status:lastStatus,StopReason:stoppedReason,Containers:containers[*].{Name:name,Reason:reason,ExitCode:exitCode}}"
```

Common causes:
- **Image pull failure**: Check ECR permissions on `ecsTaskExecutionRole`
- **Port conflict**: Ensure container port 8080 is not blocked
- **OOM killed**: Increase task memory (e.g., from 1024 to 2048 MB)
- **Secret not found**: Verify Secrets Manager ARN in task definition

### Application Not Healthy
```bash
# Check ALB target health
aws elbv2 describe-target-health \
  --target-group-arn <TARGET_GROUP_ARN> \
  --region us-east-1

# Check application logs for startup errors
aws logs tail /ecs/resortslite --follow --region us-east-1
```

Common causes:
- **JVM startup time**: Increase `healthCheckGracePeriodSeconds` (currently 300s)
- **Memcached connection failure**: Application logs a warning but continues — check `MEMCACHED_ENDPOINT`
- **EFS mount failure**: Verify EFS mount targets exist in the same subnets as ECS tasks

### Network Issues
- Ensure Security Group allows inbound 8080 from ALB
- Ensure Security Group allows outbound 443 (ECR, Secrets Manager, CloudWatch)
- Ensure Security Group allows outbound 2049 (EFS NFS)
- For private subnets: ensure NAT Gateway or VPC endpoints are configured

### CPU/Memory Errors
```
InvalidParameterException: Invalid CPU or memory value specified
```
Use only valid Fargate combinations. Default: `cpu: "512"`, `memory: "1024"`.

### Viewing ECS Events
```bash
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query "services[0].events[:10]"
```

---

## Technology-Specific Notes

### Java 8 / Spring Boot 2.7.x
- Spring Boot 2.7.x is the last 2.x release; consider upgrading to Spring Boot 3.x (requires Java 17+)
- The `eclipse-temurin:8-jre` base image is used as specified
- JVM flags `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` ensure the JVM respects container memory limits (available since Java 8u191)

### JWT Authentication (cz-java-0063)
- JWT signing secret is injected from AWS Secrets Manager via `JWT_SECRET` environment variable
- Token validity: 1 hour
- Ensure the secret is at least 32 characters (256 bits) for HS256

### Distributed Cache (cz-java-0070)
- Amazon ElastiCache for Memcached replaces the in-memory HashMap
- Set `MEMCACHED_ENDPOINT` to the ElastiCache configuration endpoint
- Cache TTL: 1 hour per booking entry
- Application degrades gracefully if Memcached is unavailable

### EFS Volumes (cz-java-0057)
- Reports are written to `/mnt/efs/reports/` (EFS-backed)
- Backups are written to `/mnt/efs/backups/nightly/` (EFS-backed)
- EFS provides shared persistent storage across all Fargate tasks
- Ensure EFS mount targets are in the same subnets as ECS tasks

### ALB Sticky Sessions (cz-java-0069)
- ALB duration-based sticky sessions are configured as a transitional measure
- Cookie: `AWSALB` / `AWSALBCORS`
- Duration: 86400 seconds (1 day)
- Spring session cookies: `SameSite=Lax`, `Secure=true`, `HttpOnly=true`
- Full stateless migration is complete via JWT; sticky sessions can be disabled once validated
