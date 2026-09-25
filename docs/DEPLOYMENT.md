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
12. [ECS Fargate Scaling and Management](#ecs-fargate-scaling-and-management)
13. [Troubleshooting](#troubleshooting)
14. [Java-Specific Notes](#java-specific-notes)

---

## Overview

**Application**: ResortsLite  
**Framework**: Spring Boot 2.7.x  
**Java Version**: 8  
**Build Tool**: Maven  
**Runtime Base Image**: `amazoncorretto:8` (explicit)  
**Target Platform**: AWS ECS Fargate  
**Application Port**: 8080  
**Health Endpoint**: `/actuator/health`

ResortsLite is a Spring Boot REST API for resort booking management. It uses JWT-based stateless authentication, Amazon ElastiCache (Memcached) for distributed caching, and EFS for report storage.

---

## Prerequisites

### Local Development
- Docker Desktop 24.x or later
- Docker Compose v2.x or later
- Java 8 JDK (for local builds outside Docker)
- Maven 3.9.x (for local builds outside Docker)

### AWS Deployment
- AWS CLI v2 configured with appropriate IAM permissions
- Python 3.x (used by deploy scripts for JSON manipulation)
- An AWS account with ECS, ECR, CloudWatch, Secrets Manager, and SSM access

---

## Local Development with Docker Compose

### 1. Build and start the application

```bash
# From the project root directory
docker compose up --build
```

### 2. Verify the application is running

```bash
curl http://localhost:8080/actuator/health
```

Expected response:
```json
{"status":"UP"}
```

### 3. Test the booking API

```bash
# Create a booking
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=DELUXE&checkIn=2024-06-01&checkOut=2024-06-05"

# Check availability
curl "http://localhost:8080/api/bookings/availability?roomType=DELUXE"
```

### 4. Override environment variables for local testing

```bash
JWT_SECRET=my-local-secret MEMCACHED_ENDPOINT=localhost:11211 docker compose up
```

### 5. Stop the application

```bash
docker compose down
```

---

## Build and Push Docker Image

### Linux/macOS

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
```

The script will prompt you to:
1. Enter an image tag (default: `latest`)
2. Select registry type (AWS ECR or Docker Hub)
3. Provide registry credentials and details

### Windows

```cmd
scripts\build-push.bat
```

### Manual Docker Build

```bash
# Build the image
docker build -t resortsLite:latest .

# Tag for ECR
docker tag resortsLite:latest 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest

# Push to ECR
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin 123456789.dkr.ecr.us-east-1.amazonaws.com
docker push 123456789.dkr.ecr.us-east-1.amazonaws.com/resortsLite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. IAM Roles

#### ECS Task Execution Role
This role allows ECS to pull images from ECR and write logs to CloudWatch.

```bash
# Create the execution role
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

# Add Secrets Manager and SSM permissions (for JWT_SECRET and MEMCACHED_ENDPOINT)
aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/SecretsManagerReadWrite

aws iam attach-role-policy \
  --role-name ecsTaskExecutionRole \
  --policy-arn arn:aws:iam::aws:policy/AmazonSSMReadOnlyAccess
```

#### ECS Task Role
This role grants the application container permissions to access AWS services.

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

### 2. VPC and Networking

```bash
# Use an existing VPC or create a new one
# Ensure you have at least 2 subnets in different AZs for high availability

# Create a security group for the application
aws ec2 create-security-group \
  --group-name resortsLite-sg \
  --description "Security group for ResortsLite ECS tasks" \
  --vpc-id vpc-xxxxxxxx

# Allow inbound traffic on port 8080
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0
```

### 3. AWS Secrets Manager — JWT Secret

```bash
aws secretsmanager create-secret \
  --name /resortsLite/jwt/secret \
  --description "JWT signing secret for ResortsLite" \
  --secret-string "your-strong-jwt-secret-at-least-32-characters-long" \
  --region us-east-1
```

### 4. AWS SSM Parameter Store — Memcached Endpoint

```bash
aws ssm put-parameter \
  --name /resortsLite/cache/memcachedEndpoint \
  --value "resortsLite-cache.abc123.cfg.use1.cache.amazonaws.com:11211" \
  --type String \
  --region us-east-1
```

### 5. CloudWatch Log Group

```bash
aws logs create-log-group \
  --log-group-name /ecs/resortsLite \
  --region us-east-1

# Set retention policy (optional)
aws logs put-retention-policy \
  --log-group-name /ecs/resortsLite \
  --retention-in-days 30 \
  --region us-east-1
```

### 6. ECR Repository

```bash
aws ecr create-repository \
  --repository-name resortsLite \
  --region us-east-1
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) configures how ECS runs the container.

### Key Settings

| Setting | Value | Reason |
|---------|-------|--------|
| `requiresCompatibilities` | `["FARGATE"]` | Serverless container execution |
| `networkMode` | `awsvpc` | Required for Fargate; each task gets its own ENI |
| `cpu` | `"512"` | 0.5 vCPU — suitable for a Spring Boot API |
| `memory` | `"1024"` | 1 GB — accommodates JVM heap + overhead |
| `executionRoleArn` | `ecsTaskExecutionRole` | Allows ECR pull and CloudWatch logging |

### Container Definition Highlights

- **Port mapping**: Container port 8080 (no host port — Fargate manages networking)
- **Environment variables**: Spring profile, JVM options, service endpoints
- **Secrets**: `JWT_SECRET` from Secrets Manager, `MEMCACHED_ENDPOINT` from SSM
- **Logging**: CloudWatch Logs via `awslogs` driver to `/ecs/resortsLite`
- **EFS mount**: `/mnt/efs/reports` for persistent report storage

### Valid Fargate CPU/Memory Combinations

| CPU | Valid Memory Options |
|-----|---------------------|
| 256 (.25 vCPU) | 512, 1024, 2048 MB |
| **512 (.5 vCPU)** | **1024, 2048, 3072, 4096 MB** ← Used |
| 1024 (1 vCPU) | 2048–8192 MB |
| 2048 (2 vCPU) | 4096–16384 MB |
| 4096 (4 vCPU) | 8192–30720 MB |

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) controls how ECS manages running tasks.

### Key Settings

| Setting | Value | Reason |
|---------|-------|--------|
| `launchType` | `FARGATE` | Serverless — no EC2 instances to manage |
| `desiredCount` | `2` | High availability across 2 AZs |
| `maximumPercent` | `200` | Allows 4 tasks during rolling deployment |
| `minimumHealthyPercent` | `50` | Keeps at least 1 task running during updates |
| `assignPublicIp` | `ENABLED` | Required if tasks need internet access (ECR pull) |

---

## ECS Fargate Deployment Walkthrough

### Step 1: Build and push the Docker image

```bash
./scripts/build-push.sh
# Select AWS ECR, enter your region and repository name
# Note the full image URI output at the end
```

### Step 2: Run the deployment script

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

You will be prompted for:
- AWS Region (e.g., `us-east-1`)
- ECS Cluster name (e.g., `resortsLite-cluster`)
- VPC ID
- Subnet IDs (comma-separated, at least 2)
- Security Group ID
- ECR Image URI (from Step 1)
- Whether to create an Application Load Balancer

### Step 3: Verify the deployment

```bash
# Check service status
aws ecs describe-services \
  --cluster resortsLite-cluster \
  --services resortsLite-service \
  --region us-east-1

# View running tasks
aws ecs list-tasks \
  --cluster resortsLite-cluster \
  --service-name resortsLite-service \
  --region us-east-1

# Stream application logs
aws logs tail /ecs/resortsLite --follow --region us-east-1
```

### Step 4: Test the application

```bash
# If using ALB (replace with your ALB DNS name)
curl http://your-alb-dns.us-east-1.elb.amazonaws.com/actuator/health

# If using direct task IP (find from ECS console or describe-tasks)
curl http://<TASK_PUBLIC_IP>:8080/actuator/health
```

### Step 5: Update the application (rolling deployment)

```bash
# Build and push new image with a new tag
./scripts/build-push.sh
# Enter new tag (e.g., v1.1.0)

# Re-run deployment script with new image URI
./scripts/deploy-image.sh
```

---

## Configuration Management

### Environment Variables Reference

| Variable | Source | Description |
|----------|--------|-------------|
| `SPRING_PROFILES_ACTIVE` | Task definition | Spring profile (`docker`) |
| `SERVER_PORT` | Task definition | Application port (8080) |
| `JAVA_OPTS` | Task definition | JVM memory and GC settings |
| `JWT_SECRET` | Secrets Manager | JWT signing key |
| `MEMCACHED_ENDPOINT` | SSM Parameter Store | ElastiCache Memcached endpoint |
| `PAYMENT_SERVICE_URL` | Task definition | Payment service endpoint |
| `REPORT_BASE_PATH` | Task definition | EFS mount path for reports |
| `TZ` | Task definition | Timezone (UTC) |

### Updating Secrets

```bash
# Update JWT secret
aws secretsmanager update-secret \
  --secret-id /resortsLite/jwt/secret \
  --secret-string "new-strong-secret-value" \
  --region us-east-1

# Update Memcached endpoint
aws ssm put-parameter \
  --name /resortsLite/cache/memcachedEndpoint \
  --value "new-endpoint:11211" \
  --type String \
  --overwrite \
  --region us-east-1
```

After updating secrets, force a new deployment to pick up the changes:

```bash
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --force-new-deployment \
  --region us-east-1
```

---

## Security Considerations

1. **Non-root container**: The application runs as `appuser` (non-root) inside the container.
2. **JWT secrets**: Never hardcode `JWT_SECRET` — always use AWS Secrets Manager.
3. **Network isolation**: Use private subnets with NAT Gateway for production; restrict security group ingress.
4. **ECR image scanning**: Enable ECR image scanning on push to detect vulnerabilities.
5. **IAM least privilege**: Scope `ecsTaskRole` permissions to only what the application needs.
6. **TLS termination**: Use ALB with HTTPS listener and ACM certificate for production traffic.
7. **Log retention**: Set CloudWatch log retention to avoid unbounded storage costs.

```bash
# Enable ECR image scanning
aws ecr put-image-scanning-configuration \
  --repository-name resortsLite \
  --image-scanning-configuration scanOnPush=true \
  --region us-east-1
```

---

## Monitoring and Observability

### CloudWatch Metrics

ECS Fargate automatically publishes metrics to CloudWatch:
- `CPUUtilization` — per service and per task
- `MemoryUtilization` — per service and per task

```bash
# View CPU utilization for the service
aws cloudwatch get-metric-statistics \
  --namespace AWS/ECS \
  --metric-name CPUUtilization \
  --dimensions Name=ClusterName,Value=resortsLite-cluster Name=ServiceName,Value=resortsLite-service \
  --start-time $(date -u -d '1 hour ago' +%Y-%m-%dT%H:%M:%SZ) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%SZ) \
  --period 300 \
  --statistics Average \
  --region us-east-1
```

### Spring Boot Actuator Endpoints

| Endpoint | URL | Description |
|----------|-----|-------------|
| Health | `/actuator/health` | Application health status |

```bash
# Check health
curl http://localhost:8080/actuator/health
```

### Application Logs

```bash
# Stream logs in real time
aws logs tail /ecs/resortsLite --follow --region us-east-1

# Filter for ERROR logs
aws logs filter-log-events \
  --log-group-name /ecs/resortsLite \
  --filter-pattern "ERROR" \
  --region us-east-1
```

---

## ECS Fargate Scaling and Management

### Manual Scaling

```bash
# Scale up to 4 tasks
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --desired-count 4 \
  --region us-east-1
```

### Auto Scaling

```bash
# Register scalable target
aws application-autoscaling register-scalable-target \
  --service-namespace ecs \
  --resource-id service/resortsLite-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --min-capacity 2 \
  --max-capacity 10 \
  --region us-east-1

# Create CPU-based scaling policy
aws application-autoscaling put-scaling-policy \
  --service-namespace ecs \
  --resource-id service/resortsLite-cluster/resortsLite-service \
  --scalable-dimension ecs:service:DesiredCount \
  --policy-name resortsLite-cpu-scaling \
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

### Blue/Green Deployment with CodeDeploy

For zero-downtime deployments, configure CodeDeploy with ECS:

```bash
# Update service to use CODE_DEPLOY deployment controller
aws ecs update-service \
  --cluster resortsLite-cluster \
  --service resortsLite-service \
  --deployment-controller type=CODE_DEPLOY \
  --region us-east-1
```

---

## Troubleshooting

### Task fails to start

```bash
# Check stopped task reason
aws ecs describe-tasks \
  --cluster resortsLite-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].{Status:lastStatus,StopCode:stopCode,StoppedReason:stoppedReason}"
```

**Common causes:**
- `CannotPullContainerError`: ECR authentication issue or image not found
- `ResourceInitializationError`: Secrets Manager/SSM access denied — check execution role
- `OutOfMemoryError`: Increase task memory in task definition

### Application health check failing

```bash
# Check application logs for startup errors
aws logs tail /ecs/resortsLite --follow --region us-east-1

# Verify the health endpoint responds
curl -v http://<TASK_IP>:8080/actuator/health
```

**Common causes:**
- JVM startup time exceeds health check `startPeriod` — increase `startPeriod` in task definition
- Missing environment variables — verify all required env vars are set
- Memcached connection failure — check `MEMCACHED_ENDPOINT` and security group rules

### Network connectivity issues

```bash
# Verify security group allows inbound on port 8080
aws ec2 describe-security-groups \
  --group-ids sg-xxxxxxxx \
  --query "SecurityGroups[0].IpPermissions"

# Check task ENI and public IP
aws ecs describe-tasks \
  --cluster resortsLite-cluster \
  --tasks <TASK_ARN> \
  --region us-east-1 \
  --query "tasks[0].attachments"
```

### Invalid CPU/memory combination

Ensure you use valid Fargate combinations. The default (`cpu: "512"`, `memory: "1024"`) is always valid.

### Service not stabilizing

```bash
# Check service events for deployment errors
aws ecs describe-services \
  --cluster resortsLite-cluster \
  --services resortsLite-service \
  --region us-east-1 \
  --query "services[0].events[:5]"
```

---

## Java-Specific Notes

### JVM Memory Configuration

The container is configured with:
```
-Xmx512m -Xms256m
-XX:+UseContainerSupport
-XX:MaxRAMPercentage=75.0
-XX:+ExitOnOutOfMemoryError
```

- `UseContainerSupport`: Enables JVM to respect container memory limits (Java 8u191+)
- `MaxRAMPercentage=75.0`: JVM uses up to 75% of container memory for heap
- `ExitOnOutOfMemoryError`: Causes container to restart on OOM (ECS will restart it)

For a 1024 MB Fargate task, the JVM heap will be approximately 768 MB.

### Spring Boot Startup Time

Spring Boot 2.7.x on Java 8 typically takes 10–20 seconds to start. The ECS service health check grace period should be at least 60 seconds to avoid premature task termination.

### Graceful Shutdown

The Dockerfile uses `exec java $JAVA_OPTS -jar /app/app.jar` which ensures the JVM receives SIGTERM directly from ECS, allowing Spring Boot to perform graceful shutdown (completing in-flight requests).

### H2 In-Memory Database

The application uses H2 in-memory database. **This is not suitable for production** — data is lost on container restart. For production, migrate to Amazon RDS (PostgreSQL or MySQL) and update `spring.datasource.*` properties accordingly.

### Memcached (ElastiCache)

The application uses spymemcached to connect to Amazon ElastiCache. Ensure:
1. The ElastiCache cluster is in the same VPC as the ECS tasks
2. The ECS security group allows outbound TCP on port 11211 to the ElastiCache security group
3. The `MEMCACHED_ENDPOINT` SSM parameter is set to the ElastiCache configuration endpoint

### ALB Sticky Sessions

The application includes `ecs-alb-stickiness.json` for configuring ALB target group stickiness. This is a transitional measure for session affinity while migrating to fully distributed session storage. Apply it with:

```bash
aws elbv2 modify-target-group-attributes \
  --target-group-arn <TARGET_GROUP_ARN> \
  --attributes file://ecs-alb-stickiness.json \
  --region us-east-1
```
