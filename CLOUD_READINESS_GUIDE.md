# Cloud Readiness Configuration Guide

## Overview
This application has been transformed to be fully cloud-ready for AWS deployment. All cloud compatibility blockers have been resolved.

## AWS Services Required

### 1. Amazon ElastiCache for Redis
**Purpose**: Distributed session management and caching (fixes cr-java-0065, cr-java-0067)

**Configuration**:
- Create an ElastiCache Redis cluster
- Set environment variables:
  - `REDIS_HOST`: Redis endpoint hostname
  - `REDIS_PORT`: Redis port (default: 6379)
  - `REDIS_PASSWORD`: Redis authentication password (if enabled)

### 2. Amazon S3
**Purpose**: Cloud-native file storage (fixes cr-java-0061, cr-java-0062, cr-java-0063)

**Configuration**:
- Create an S3 bucket for reports storage
- Set environment variables:
  - `S3_REPORTS_BUCKET`: S3 bucket name for reports
  - `AWS_REGION`: AWS region (e.g., us-east-1)

### 3. AWS Secrets Manager
**Purpose**: Secure credential storage (fixes cr-java-0069, cr-java-0090)

**Configuration**:
- Create secrets for database credentials:
  ```json
  {
    "host": "database-endpoint.rds.amazonaws.com",
    "username": "admin",
    "password": "secure-password"
  }
  ```
- Create secrets for authentication credentials
- Set environment variables:
  - `SECRETS_DB_NAME`: Secret name for database credentials
  - `SECRETS_AUTH_NAME`: Secret name for authentication credentials

### 4. AWS Systems Manager Parameter Store
**Purpose**: Externalized configuration (fixes cr-java-0071, cr-java-0077)

**Configuration**:
- Store service endpoints as parameters
- Set environment variables:
  - `PAYMENT_ENDPOINT`: Payment service URL
  - `INVENTORY_ENDPOINT`: Inventory service URL
  - `NOTIFICATION_ENDPOINT`: Notification service URL
  - `REPORTS_BASE_URL`: Reports base URL

## Environment Variables

### Required Variables
```bash
# Server Configuration
SERVER_PORT=8080

# Database Configuration
DB_URL=jdbc:postgresql://database-endpoint.rds.amazonaws.com:5432/resorts
DB_USERNAME=admin
DB_PASSWORD=secure-password

# Redis Configuration (Amazon ElastiCache)
REDIS_HOST=redis-cluster.cache.amazonaws.com
REDIS_PORT=6379
REDIS_PASSWORD=redis-auth-token

# AWS Configuration
AWS_REGION=us-east-1
S3_REPORTS_BUCKET=resorts-reports-bucket

# AWS Secrets Manager
SECRETS_DB_NAME=resorts-db-credentials
SECRETS_AUTH_NAME=resorts-auth-credentials

# Service Endpoints
PAYMENT_ENDPOINT=https://payment-svc.internal:9090/charge
INVENTORY_ENDPOINT=https://inventory-svc.internal:8081/rooms
NOTIFICATION_ENDPOINT=https://notify.internal:7070/send
REPORTS_BASE_URL=https://reports.resorts-internal.com
```

## IAM Permissions Required

The application requires the following IAM permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:PutObject",
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::resorts-reports-bucket",
        "arn:aws:s3:::resorts-reports-bucket/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": [
        "arn:aws:secretsmanager:*:*:secret:resorts-db-credentials-*",
        "arn:aws:secretsmanager:*:*:secret:resorts-auth-credentials-*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameter",
        "ssm:GetParameters"
      ],
      "Resource": [
        "arn:aws:ssm:*:*:parameter/resorts/*"
      ]
    }
  ]
}
```

## Deployment Options

### AWS Elastic Container Service (ECS)
- Deploy as ECS tasks with Fargate or EC2 launch type
- Configure task definition with environment variables
- Attach IAM role with required permissions

### AWS Elastic Kubernetes Service (EKS)
- Deploy as Kubernetes pods
- Use ConfigMaps and Secrets for configuration
- Configure service account with IAM role (IRSA)

### AWS Elastic Beanstalk
- Deploy as Java application
- Configure environment properties
- Attach instance profile with required permissions

## Cloud Readiness Fixes Applied

### File System Dependencies (Critical)
- **cr-java-0061**: Replaced hard-coded file paths with S3 configuration
- **cr-java-0062**: Migrated local file writes to Amazon S3
- **cr-java-0063**: Replaced java.io.File operations with S3 SDK

### Configuration Management (Critical)
- **cr-java-0069**: Externalized database credentials to AWS Secrets Manager
- **cr-java-0071**: Externalized environment URLs to Parameter Store

### Networking (Critical)
- **cr-java-0077**: Replaced hard-coded ports with environment variables

### State Management (High)
- **cr-java-0065**: Migrated HTTP session storage to Amazon ElastiCache for Redis
- **cr-java-0067**: Replaced in-memory cache with Redis with TTL

### Security (High)
- **cr-java-0090**: Replaced file-based authentication with AWS Secrets Manager

### Time Management (High)
- **cr-java-0111**: Migrated to java.time API with UTC standardization

## Testing

### Local Testing
1. Start Redis locally: `docker run -p 6379:6379 redis:latest`
2. Configure AWS credentials: `aws configure`
3. Set environment variables
4. Run application: `mvn spring-boot:run`

### Cloud Testing
1. Deploy to AWS environment
2. Verify ElastiCache connectivity
3. Verify S3 bucket access
4. Verify Secrets Manager access
5. Test session persistence across multiple instances

## Monitoring

### CloudWatch Metrics
- Monitor Redis connection pool metrics
- Monitor S3 API call metrics
- Monitor Secrets Manager API call metrics

### Application Logs
- All logs use UTC timestamps (cr-java-0111 fix)
- Structured logging for cloud monitoring
- CloudWatch Logs integration recommended

## Security Best Practices

1. **Never commit credentials**: All credentials are externalized
2. **Use IAM roles**: Attach IAM roles to ECS tasks/EC2 instances
3. **Enable encryption**: Enable encryption at rest for S3 and Redis
4. **Use VPC endpoints**: Configure VPC endpoints for AWS services
5. **Enable audit logging**: Enable CloudTrail for API audit logs

## Troubleshooting

### Redis Connection Issues
- Verify security group allows inbound traffic on port 6379
- Verify Redis endpoint is accessible from application
- Check Redis authentication credentials

### S3 Access Issues
- Verify IAM permissions for S3 bucket
- Verify bucket exists and is in correct region
- Check bucket policy and CORS configuration

### Secrets Manager Issues
- Verify IAM permissions for Secrets Manager
- Verify secret names match configuration
- Check secret JSON format matches expected structure
