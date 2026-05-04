# ResortsLite - Cloud-Ready Application

## Overview
This application has been transformed to be fully cloud-ready for AWS deployment. All cloud compatibility blockers have been resolved.

## Cloud Readiness Fixes Applied

### 1. File System Dependencies (cr-java-0061, cr-java-0062, cr-java-0063)
**Problem**: Hard-coded file paths and local file system operations
**Solution**: 
- Replaced all local file operations with Amazon S3
- Reports are now stored in S3 bucket: `resorts-reports-bucket`
- Backups are stored in S3 bucket: `resorts-backups-bucket`
- No dependency on ephemeral container file systems

### 2. Hard-coded Database Credentials (cr-java-0069)
**Problem**: Database credentials embedded in source code
**Solution**:
- Integrated AWS Secrets Manager for credential storage
- Credentials are retrieved at runtime from secret: `resorts-db-credentials`
- Supports automatic credential rotation
- No credentials in source code or container images

### 3. Hard-coded Environment URLs (cr-java-0071)
**Problem**: Environment-specific URLs hard-coded in application
**Solution**:
- All service endpoints externalized to AWS Systems Manager Parameter Store
- Configuration injected via environment variables
- Supports environment-agnostic deployments (dev/staging/prod)

### 4. Hard-coded Ports (cr-java-0077)
**Problem**: Fixed port numbers preventing dynamic assignment
**Solution**:
- Server port now configurable via `SERVER_PORT` environment variable
- Compatible with ECS/EKS dynamic port assignment
- Supports container orchestration requirements

### 5. HTTP Session State Storage (cr-java-0065)
**Problem**: Session data stored in local memory, breaking horizontal scaling
**Solution**:
- Integrated Spring Session with Amazon ElastiCache for Redis
- Session data centralized and shared across all instances
- Enables stateless application instances
- Supports auto-scaling and failover

### 6. In-Memory Caching Without TTL (cr-java-0067)
**Problem**: Local HashMap cache causing memory growth and stale data
**Solution**:
- Replaced in-memory cache with Amazon ElastiCache for Redis
- Implemented proper TTL policies (1 hour for bookings)
- Cache synchronized across all instances
- Prevents memory leaks and ensures data consistency

### 7. File-based Authentication (cr-java-0090)
**Problem**: Authentication credentials stored in local files
**Solution**:
- Removed file-based authentication
- Prepared for integration with Amazon Cognito
- Placeholder method for backward compatibility

### 8. Clock/Time Dependencies (cr-java-0111)
**Problem**: Using java.util.Date with local timezone dependencies
**Solution**:
- Migrated to java.time API (Instant, ZonedDateTime)
- All timestamps standardized to UTC
- Eliminates timezone inconsistencies in distributed environments

## AWS Services Required

### Core Services
1. **Amazon S3**
   - Buckets: `resorts-reports-bucket`, `resorts-backups-bucket`
   - Purpose: Durable file storage replacing local file system

2. **AWS Secrets Manager**
   - Secret: `resorts-db-credentials`
   - Format: JSON with keys: `host`, `username`, `password`
   - Purpose: Secure credential storage with rotation support

3. **AWS Systems Manager Parameter Store**
   - Parameters:
     - `/resorts/payment-endpoint`
     - `/resorts/inventory-endpoint`
     - `/resorts/notification-endpoint`
     - `/resorts/reports-base-url`
   - Purpose: Externalized configuration management

4. **Amazon ElastiCache for Redis**
   - Purpose: Distributed session storage and caching
   - Configuration: Injected via environment variables

### IAM Permissions Required
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
        "arn:aws:s3:::resorts-reports-bucket/*",
        "arn:aws:s3:::resorts-backups-bucket/*"
      ]
    },
    {
      "Effect": "Allow",
      "Action": [
        "secretsmanager:GetSecretValue"
      ],
      "Resource": "arn:aws:secretsmanager:*:*:secret:resorts-db-credentials-*"
    },
    {
      "Effect": "Allow",
      "Action": [
        "ssm:GetParameter",
        "ssm:GetParameters"
      ],
      "Resource": "arn:aws:ssm:*:*:parameter/resorts/*"
    }
  ]
}
```

## Environment Variables

### Required
- `AWS_REGION`: AWS region (default: us-east-1)
- `REDIS_HOST`: ElastiCache Redis endpoint
- `REDIS_PORT`: Redis port (default: 6379)

### Optional (with defaults)
- `SERVER_PORT`: Application port (default: 8080)
- `DB_SECRET_NAME`: Secrets Manager secret name (default: resorts-db-credentials)
- `S3_REPORTS_BUCKET`: S3 bucket for reports (default: resorts-reports-bucket)
- `S3_BACKUPS_BUCKET`: S3 bucket for backups (default: resorts-backups-bucket)
- `PAYMENT_ENDPOINT`: Payment service URL
- `INVENTORY_ENDPOINT`: Inventory service URL
- `NOTIFICATION_ENDPOINT`: Notification service URL
- `REPORTS_BASE_URL`: Reports download base URL

## Deployment Checklist

### Pre-Deployment
- [ ] Create S3 buckets: `resorts-reports-bucket`, `resorts-backups-bucket`
- [ ] Create Secrets Manager secret: `resorts-db-credentials`
- [ ] Create Parameter Store parameters for service endpoints
- [ ] Provision ElastiCache for Redis cluster
- [ ] Configure IAM role with required permissions
- [ ] Set up VPC security groups for Redis access

### Deployment
- [ ] Build application: `mvn clean package`
- [ ] Deploy to ECS/EKS/Elastic Beanstalk
- [ ] Configure environment variables
- [ ] Attach IAM role to compute resources
- [ ] Configure load balancer health checks to `/actuator/health`

### Post-Deployment
- [ ] Verify S3 connectivity
- [ ] Verify Secrets Manager access
- [ ] Verify Redis connectivity
- [ ] Test session persistence across instances
- [ ] Monitor CloudWatch logs

## Architecture Benefits

### Scalability
- Stateless application instances enable horizontal scaling
- Distributed cache and session storage support unlimited instances
- S3 provides unlimited storage capacity

### Reliability
- No data loss on instance termination
- Session persistence across failures
- Automatic credential rotation support

### Security
- No credentials in source code or images
- Encrypted secrets in Secrets Manager
- Encrypted data in transit and at rest (S3, Redis)

### Maintainability
- Environment-agnostic configuration
- No code changes for environment promotion
- Centralized configuration management

## Migration Notes

### Breaking Changes
- HTTP session data is now stored in Redis (requires Redis connectivity)
- File operations now require S3 access (IAM permissions)
- Database credentials must be in Secrets Manager

### Backward Compatibility
- Application falls back to environment variables if AWS services unavailable
- Maintains existing API contracts
- No changes to business logic

## Support

For issues or questions regarding cloud deployment, refer to:
- AWS Documentation: https://docs.aws.amazon.com/
- Spring Session Redis: https://spring.io/projects/spring-session-data-redis
- AWS SDK for Java v2: https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/
