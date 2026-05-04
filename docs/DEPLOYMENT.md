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

ResortsLite is a Spring Boot 2.7.18 application built with Java 8 that provides resort booking functionality. This guide covers containerization and deployment to AWS ECS Fargate.

**Application Details:**
- **Framework:** Spring Boot 2.7.18
- **Java Version:** 1.8
- **Build Tool:** Maven
- **Application Port:** 8080
- **Health Endpoint:** `/actuator/health`
- **Management Endpoints:** `/actuator/health`, `/actuator/info`

**External Dependencies:**
- Redis (for distributed session management)
- AWS S3 (for file storage)
- H2 Database (in-memory, for demo purposes)
- Notification Service (external HTTP endpoint)

---

## Prerequisites

### Required Software
- **Docker Desktop** (version 20.10 or later)
  - Download: https://www.docker.com/products/docker-desktop
- **AWS CLI** (version 2.x)
  - Download: https://aws.amazon.com/cli/
  - Configure: `aws configure`
- **Git** (for version control)
- **Java 8 JDK** (for local development)
- **Maven 3.6+** (for local builds)

### AWS Account Requirements
- Active AWS account with appropriate permissions
- IAM user with permissions for:
  - ECS (create/update clusters, services, task definitions)
  - ECR (create repositories, push images)
  - EC2 (VPC, subnets, security groups)
  - CloudWatch Logs (create log groups)
  - IAM (create/manage roles)
  - Elastic Load Balancing (create ALB, target groups)

### AWS CLI Configuration
```bash
# Configure AWS CLI with your credentials
aws configure

# Verify configuration
aws sts get-caller-identity
```

---

## Local Development Setup

### 1. Clone the Repository
```bash
git clone <repository-url>
cd fullcomp
```

### 2. Build the Application Locally
```bash
# Using Maven
mvn clean package -DskipTests

# Verify the JAR file
ls -lh target/*.jar
```

### 3. Run Locally with Docker Compose
```bash
# Build and start the application
docker-compose up --build

# Access the application
# Application: http://localhost:8080
# Health Check: http://localhost:8080/actuator/health
# H2 Console: http://localhost:8080/h2-console

# Stop the application
docker-compose down
```

### 4. Environment Variables for Local Development
Create a `.env` file in the project root:
```env
REDIS_HOST=localhost
REDIS_PORT=6379
REDIS_PASSWORD=
S3_BUCKET_NAME=resorts-lite-files
AWS_REGION=us-east-1
AWS_ACCESS_KEY_ID=your-access-key
AWS_SECRET_ACCESS_KEY=your-secret-key
APP_NOTIFICATION_ENDPOINT=http://notify.internal:7070/send
```

---

## Building and Pushing Docker Images

### Option 1: Using build-push.sh (Linux/macOS)

```bash
# Make the script executable
chmod +x scripts/build-push.sh

# Run the script
./scripts/build-push.sh
```

**Script Workflow:**
1. Prompts for image tag (default: `latest`)
2. Asks to select registry (AWS ECR or Docker Hub)
3. Prompts for registry-specific credentials
4. Builds the Docker image
5. Pushes to the selected registry

**Example - AWS ECR:**
```
Enter image tag (default: latest): v1.0.0
Select Docker Registry:
1. AWS ECR (Elastic Container Registry)
2. Docker Hub
Enter choice (1 or 2): 1

Enter AWS Region (e.g., us-east-1): us-east-1
Enter AWS Account ID: 123456789012
Enter ECR Repository Name (default: resortslite): resortslite
```

**Example - Docker Hub:**
```
Enter image tag (default: latest): v1.0.0
Select Docker Registry:
1. AWS ECR (Elastic Container Registry)
2. Docker Hub
Enter choice (1 or 2): 2

Enter Docker Hub username: myusername
Enter Docker Hub password/token: ********
```

### Option 2: Using build-push.bat (Windows)

```cmd
# Run the script
scripts\build-push.bat
```

Follow the same prompts as the Linux/macOS version.

### Manual Docker Build and Push

#### AWS ECR
```bash
# Authenticate with ECR
aws ecr get-login-password --region us-east-1 | docker login --username AWS --password-stdin 123456789012.dkr.ecr.us-east-1.amazonaws.com

# Create ECR repository (if not exists)
aws ecr create-repository --repository-name resortslite --region us-east-1

# Build image
docker build -t resortslite:latest .

# Tag image
docker tag resortslite:latest 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest

# Push image
docker push 123456789012.dkr.ecr.us-east-1.amazonaws.com/resortslite:latest
```

#### Docker Hub
```bash
# Login to Docker Hub
docker login

# Build image
docker build -t myusername/resortslite:latest .

# Push image
docker push myusername/resortslite:latest
```

---

## AWS ECS Fargate Prerequisites

### 1. VPC and Networking Setup

**Create VPC (if not exists):**
```bash
# Create VPC
aws ec2 create-vpc --cidr-block 10.0.0.0/16 --region us-east-1

# Create subnets in different availability zones
aws ec2 create-subnet --vpc-id vpc-xxxxx --cidr-block 10.0.1.0/24 --availability-zone us-east-1a
aws ec2 create-subnet --vpc-id vpc-xxxxx --cidr-block 10.0.2.0/24 --availability-zone us-east-1b

# Create Internet Gateway
aws ec2 create-internet-gateway
aws ec2 attach-internet-gateway --vpc-id vpc-xxxxx --internet-gateway-id igw-xxxxx

# Create route table and associate with subnets
aws ec2 create-route-table --vpc-id vpc-xxxxx
aws ec2 create-route --route-table-id rtb-xxxxx --destination-cidr-block 0.0.0.0/0 --gateway-id igw-xxxxx
```

### 2. Security Group Configuration

**Create Security Group:**
```bash
# Create security group
aws ec2 create-security-group \
  --group-name resortslite-sg \
  --description "Security group for ResortsLite ECS tasks" \
  --vpc-id vpc-xxxxx

# Allow inbound traffic on port 8080 (application)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 8080 \
  --cidr 0.0.0.0/0

# Allow inbound traffic on port 80 (ALB)
aws ec2 authorize-security-group-ingress \
  --group-id sg-xxxxx \
  --protocol tcp \
  --port 80 \
  --cidr 0.0.0.0/0
```

### 3. IAM Roles Setup

**ECS Task Execution Role:**
```bash
# Create trust policy file
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

**ECS Task Role (for application permissions):**
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

### 4. CloudWatch Logs Setup

```bash
# Create log group
aws logs create-log-group --log-group-name /ecs/resortslite --region us-east-1

# Set retention policy (optional)
aws logs put-retention-policy \
  --log-group-name /ecs/resortslite \
  --retention-in-days 7
```

---

## ECS Task Definition Explained

The task definition (`ecs/task-definition.json`) defines how your container runs on ECS Fargate.

### Key Components

**1. Launch Type Configuration:**
```json
{
  "requiresCompatibilities": ["FARGATE"],
  "networkMode": "awsvpc"
}
```
- `FARGATE`: Serverless compute engine
- `awsvpc`: Each task gets its own ENI and private IP

**2. CPU and Memory:**
```json
{
  "cpu": "512",
  "memory": "1024"
}
```
- CPU: 512 units = 0.5 vCPU
- Memory: 1024 MB = 1 GB

**Valid Fargate CPU/Memory Combinations:**
| CPU (vCPU) | Memory (MB) |
|------------|-------------|
| 256 (.25)  | 512, 1024, 2048 |
| 512 (.5)   | 1024, 2048, 3072, 4096 |
| 1024 (1)   | 2048-8192 (increments of 1024) |
| 2048 (2)   | 4096-16384 (increments of 1024) |
| 4096 (4)   | 8192-30720 (increments of 1024) |

**3. IAM Roles:**
```json
{
  "executionRoleArn": "arn:aws:iam::{{ACCOUNT_ID}}:role/ecsTaskExecutionRole",
  "taskRoleArn": "arn:aws:iam::{{ACCOUNT_ID}}:role/ecsTaskRole"
}
```
- `executionRoleArn`: Allows ECS to pull images and write logs
- `taskRoleArn`: Allows application to access AWS services (S3, etc.)

**4. Container Definition:**
```json
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
```

**5. Environment Variables:**
- JVM settings: `JAVA_OPTS`
- Spring profiles: `SPRING_PROFILES_ACTIVE`
- External service connections: Redis, S3, etc.

**6. Logging Configuration:**
```json
{
  "logConfiguration": {
    "logDriver": "awslogs",
    "options": {
      "awslogs-group": "/ecs/resortslite",
      "awslogs-region": "{{AWS_REGION}}",
      "awslogs-stream-prefix": "ecs"
    }
  }
}
```

---

## ECS Service Configuration

The service definition (`ecs/service-definition.json`) manages the deployment and scaling of tasks.

### Key Components

**1. Service Configuration:**
```json
{
  "serviceName": "resortslite-service",
  "desiredCount": 2,
  "launchType": "FARGATE"
}
```
- `desiredCount`: Number of task instances to run

**2. Network Configuration:**
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
- Use at least 2 subnets in different AZs for high availability
- `assignPublicIp`: Required if tasks need internet access

**3. Load Balancer Configuration:**
```json
{
  "loadBalancers": [
    {
      "targetGroupArn": "{{TARGET_GROUP_ARN}}",
      "containerName": "resortslite",
      "containerPort": 8080
    }
  ],
  "healthCheckGracePeriodSeconds": 300
}
```
- Target group must use `target-type: ip` (required for Fargate)
- Health check grace period allows time for application startup

**4. Deployment Configuration:**
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
- Rolling deployment strategy
- Circuit breaker automatically rolls back failed deployments

---

## Deployment to AWS ECS Fargate

### Option 1: Using deploy-image.sh (Linux/macOS)

```bash
# Make the script executable
chmod +x scripts/deploy-image.sh

# Run the deployment script
./scripts/deploy-image.sh
```

**Script Workflow:**
1. Prompts for AWS region and ECS cluster name
2. Creates cluster if it doesn't exist
3. Prompts for VPC and network configuration
4. Prompts for Docker image URI
5. Prompts for external service configurations (Redis, S3, etc.)
6. Asks if load balancer is needed
7. Creates ALB and target group (if requested)
8. Registers task definition
9. Creates or updates ECS service
10. Waits for service to stabilize
11. Displays deployment status and access URLs

### Option 2: Using deploy-image.bat (Windows)

```cmd
# Run the deployment script
scripts\deploy-image.bat
```

Follow the same prompts as the Linux/macOS version.

### Manual Deployment Steps

#### 1. Register Task Definition
```bash
# Update placeholders in task-definition.json
# Then register
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1
```

#### 2. Create ECS Cluster
```bash
aws ecs create-cluster \
  --cluster-name resortslite-cluster \
  --region us-east-1
```

#### 3. Create Target Group (if using ALB)
```bash
aws elbv2 create-target-group \
  --name resortslite-tg \
  --protocol HTTP \
  --port 8080 \
  --vpc-id vpc-xxxxx \
  --target-type ip \
  --health-check-path /actuator/health \
  --region us-east-1
```

#### 4. Create Application Load Balancer
```bash
aws elbv2 create-load-balancer \
  --name resortslite-alb \
  --subnets subnet-xxxxx subnet-yyyyy \
  --security-groups sg-xxxxx \
  --region us-east-1
```

#### 5. Create Listener
```bash
aws elbv2 create-listener \
  --load-balancer-arn arn:aws:elasticloadbalancing:... \
  --protocol HTTP \
  --port 80 \
  --default-actions Type=forward,TargetGroupArn=arn:aws:elasticloadbalancing:...
```

#### 6. Create ECS Service
```bash
aws ecs create-service \
  --cli-input-json file://ecs/service-definition.json \
  --region us-east-1
```

#### 7. Wait for Service Stability
```bash
aws ecs wait services-stable \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1
```

---

## Monitoring and Logging

### CloudWatch Logs

**View Logs:**
```bash
# List log streams
aws logs describe-log-streams \
  --log-group-name /ecs/resortslite \
  --region us-east-1

# Tail logs
aws logs tail /ecs/resortslite --follow --region us-east-1
```

**Console Access:**
- Navigate to: CloudWatch > Log groups > /ecs/resortslite
- URL: https://console.aws.amazon.com/cloudwatch/home?region=us-east-1#logsV2:log-groups/log-group/$252Fecs$252Fresortslite

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

### Application Health Checks

**Health Endpoint:**
```bash
# Via ALB
curl http://<alb-dns-name>/actuator/health

# Direct to task (if public IP assigned)
curl http://<task-public-ip>:8080/actuator/health
```

**Expected Response:**
```json
{
  "status": "UP",
  "components": {
    "db": {
      "status": "UP"
    },
    "diskSpace": {
      "status": "UP"
    },
    "ping": {
      "status": "UP"
    },
    "redis": {
      "status": "UP"
    }
  }
}
```

---

## Troubleshooting

### Common Issues and Solutions

#### 1. Task Fails to Start

**Symptoms:**
- Tasks transition from PENDING to STOPPED
- No logs in CloudWatch

**Possible Causes:**
- Invalid CPU/memory combination
- Image pull errors
- IAM role permissions

**Solutions:**
```bash
# Check task stopped reason
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1 \
  --query 'tasks[0].stoppedReason'

# Verify IAM role
aws iam get-role --role-name ecsTaskExecutionRole

# Check ECR permissions
aws ecr get-login-password --region us-east-1
```

#### 2. Application Not Accessible via ALB

**Symptoms:**
- ALB returns 503 Service Unavailable
- Health checks failing

**Possible Causes:**
- Security group not allowing traffic
- Target group health check misconfigured
- Application not listening on correct port

**Solutions:**
```bash
# Check target health
aws elbv2 describe-target-health \
  --target-group-arn <target-group-arn>

# Verify security group rules
aws ec2 describe-security-groups --group-ids sg-xxxxx

# Check application logs
aws logs tail /ecs/resortslite --follow
```

#### 3. Out of Memory Errors

**Symptoms:**
- Tasks being killed
- OOMKilled in task stopped reason

**Solutions:**
- Increase task memory in task definition
- Adjust JVM heap settings in JAVA_OPTS
- Monitor memory usage with CloudWatch Container Insights

```json
{
  "cpu": "1024",
  "memory": "2048"
}
```

```bash
# Update JAVA_OPTS
JAVA_OPTS="-Xmx1536m -Xms768m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

#### 4. Redis Connection Failures

**Symptoms:**
- Application logs show Redis connection errors
- Health check shows Redis DOWN

**Solutions:**
- Verify Redis endpoint and port
- Check security group allows traffic to Redis
- Verify Redis password (if required)

```bash
# Test Redis connectivity from task
aws ecs execute-command \
  --cluster resortslite-cluster \
  --task <task-id> \
  --container resortslite \
  --interactive \
  --command "/bin/sh"

# Inside container
nc -zv <redis-host> 6379
```

#### 5. S3 Access Denied

**Symptoms:**
- Application logs show S3 access denied errors
- File upload/download failures

**Solutions:**
- Verify task role has S3 permissions
- Check S3 bucket policy
- Verify AWS region matches bucket region

```bash
# Check task role policies
aws iam list-attached-role-policies --role-name ecsTaskRole

# Test S3 access
aws s3 ls s3://<bucket-name> --region us-east-1
```

### Debugging Commands

```bash
# View task details
aws ecs describe-tasks \
  --cluster resortslite-cluster \
  --tasks <task-id> \
  --region us-east-1

# View service events
aws ecs describe-services \
  --cluster resortslite-cluster \
  --services resortslite-service \
  --region us-east-1 \
  --query 'services[0].events'

# View CloudWatch logs
aws logs tail /ecs/resortslite --follow --region us-east-1

# Execute command in running task (requires ECS Exec enabled)
aws ecs execute-command \
  --cluster resortslite-cluster \
  --task <task-id> \
  --container resortslite \
  --interactive \
  --command "/bin/bash"
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
  --target-tracking-scaling-policy-configuration file://scaling-policy.json \
  --region us-east-1
```

**scaling-policy.json:**
```json
{
  "TargetValue": 70.0,
  "PredefinedMetricSpecification": {
    "PredefinedMetricType": "ECSServiceAverageCPUUtilization"
  },
  "ScaleInCooldown": 300,
  "ScaleOutCooldown": 60
}
```

### Blue/Green Deployments

**Using AWS CodeDeploy:**
1. Create CodeDeploy application and deployment group
2. Configure deployment settings
3. Deploy new task definition revision

```bash
# Create deployment
aws deploy create-deployment \
  --application-name resortslite-app \
  --deployment-group-name resortslite-dg \
  --revision revisionType=AppSpecContent,appSpecContent={content='...'} \
  --region us-east-1
```

### Rolling Updates

**Update Task Definition:**
```bash
# Register new task definition
aws ecs register-task-definition \
  --cli-input-json file://ecs/task-definition.json \
  --region us-east-1

# Update service with new task definition
aws ecs update-service \
  --cluster resortslite-cluster \
  --service resortslite-service \
  --task-definition resortslite-task:2 \
  --region us-east-1
```

---

## Security Considerations

### 1. Network Security

**Best Practices:**
- Use private subnets for ECS tasks
- Place ALB in public subnets
- Restrict security group rules to minimum required
- Use VPC endpoints for AWS services (ECR, S3, CloudWatch)

**Example Security Group Rules:**
```bash
# Allow inbound from ALB only
aws ec2 authorize-security-group-ingress \
  --group-id sg-task \
  --protocol tcp \
  --port 8080 \
  --source-group sg-alb

# Allow outbound to Redis
aws ec2 authorize-security-group-egress \
  --group-id sg-task \
  --protocol tcp \
  --port 6379 \
  --destination-group sg-redis
```

### 2. Secrets Management

**Use AWS Secrets Manager:**
```bash
# Store Redis password
aws secretsmanager create-secret \
  --name resortslite/redis-password \
  --secret-string "your-redis-password" \
  --region us-east-1

# Reference in task definition
{
  "secrets": [
    {
      "name": "REDIS_PASSWORD",
      "valueFrom": "arn:aws:secretsmanager:us-east-1:123456789012:secret:resortslite/redis-password"
    }
  ]
}
```

### 3. IAM Least Privilege

**Task Role Policy Example:**
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

### 4. Image Scanning

**Enable ECR Image Scanning:**
```bash
aws ecr put-image-scanning-configuration \
  --repository-name resortslite \
  --image-scanning-configuration scanOnPush=true \
  --region us-east-1
```

### 5. Encryption

**Enable Encryption:**
- CloudWatch Logs: Use KMS encryption
- S3 Bucket: Enable server-side encryption
- Secrets Manager: Encrypted by default with KMS

---

## Technology-Specific Notes

### Spring Boot Configuration

**Profiles:**
- Use `SPRING_PROFILES_ACTIVE=docker` for containerized environment
- Override properties via environment variables

**Actuator Endpoints:**
- Health: `/actuator/health`
- Info: `/actuator/info`
- Metrics: `/actuator/metrics` (if enabled)

**JVM Tuning:**
```bash
# Recommended JVM options for containers
JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -Djava.security.egd=file:/dev/./urandom"
```

### Maven Build Optimization

**Dependency Caching:**
- Dockerfile copies `pom.xml` first
- Downloads dependencies before copying source
- Leverages Docker layer caching

**Build Performance:**
```bash
# Skip tests for faster builds
mvn clean package -DskipTests

# Use offline mode if dependencies cached
mvn clean package -DskipTests -o
```

### H2 Database Considerations

**In-Memory Database:**
- Data is lost when container restarts
- Suitable for development/demo only
- For production, use RDS or Aurora

**Migration to RDS:**
```properties
# Update application.properties
spring.datasource.url=jdbc:postgresql://rds-endpoint:5432/resortdb
spring.datasource.username=${DB_USERNAME}
spring.datasource.password=${DB_PASSWORD}
spring.datasource.driver-class-name=org.postgresql.Driver
```

---

## Additional Resources

### AWS Documentation
- [ECS Fargate Documentation](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/AWS_Fargate.html)
- [ECS Task Definitions](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/task_definitions.html)
- [ECS Service Auto Scaling](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/service-auto-scaling.html)

### Spring Boot Resources
- [Spring Boot Docker Guide](https://spring.io/guides/gs/spring-boot-docker/)
- [Spring Boot Actuator](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)
- [Spring Boot Production Ready](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html#actuator.endpoints)

### Docker Best Practices
- [Docker Best Practices](https://docs.docker.com/develop/dev-best-practices/)
- [Multi-stage Builds](https://docs.docker.com/develop/develop-images/multistage-build/)

---

## Support and Maintenance

### Regular Maintenance Tasks

1. **Update Base Images:**
   ```bash
   # Rebuild with latest base image
   docker build --no-cache -t resortslite:latest .
   ```

2. **Review CloudWatch Logs:**
   - Set up log retention policies
   - Create CloudWatch alarms for errors

3. **Monitor Costs:**
   - Review ECS Fargate usage
   - Optimize task CPU/memory allocation
   - Use Savings Plans for predictable workloads

4. **Security Updates:**
   - Regularly scan images for vulnerabilities
   - Update dependencies in pom.xml
   - Rotate secrets and credentials

### Getting Help

- **AWS Support:** https://console.aws.amazon.com/support/
- **ECS Forums:** https://forums.aws.amazon.com/forum.jspa?forumID=187
- **Spring Boot Community:** https://spring.io/community

---

## Conclusion

This guide provides comprehensive instructions for containerizing and deploying the ResortsLite application to AWS ECS Fargate. Follow the steps carefully, and refer to the troubleshooting section for common issues.

For production deployments, ensure you:
- Use private subnets and VPC endpoints
- Enable auto-scaling
- Set up monitoring and alerting
- Implement proper secrets management
- Use RDS instead of H2 database
- Enable encryption at rest and in transit
- Implement CI/CD pipelines for automated deployments

Happy deploying! 🚀
