# ResortsLite - Cloud-Ready Application for GCP

## Overview
This application has been transformed to be fully cloud-ready and compatible with Google Cloud Platform (GCP) deployment. All cloud compatibility blockers have been resolved.

## Cloud Readiness Fixes Applied

### 1. File System Dependencies (cr-java-0061, cr-java-0062, cr-java-0063)
**Problem**: Hardcoded file paths and local file system operations
**Solution**: 
- Replaced all file operations with Google Cloud Storage (GCS)
- Added `google-cloud-storage` dependency
- Created `GcpConfig` for GCS client configuration
- Updated `ReportService` to use GCS for all file operations

### 2. Configuration Management (cr-java-0069, cr-java-0071)
**Problem**: Hardcoded database credentials and environment URLs
**Solution**:
- Externalized all configuration to environment variables
- Integrated Google Secret Manager via `spring-cloud-gcp-starter-secretmanager`
- Updated `application.properties` to use `${ENV_VAR}` syntax
- Removed hardcoded credentials from `BookingService`

### 3. Networking & Communication (cr-java-0077)
**Problem**: Hardcoded port numbers
**Solution**:
- Changed `server.port` to `${PORT:8080}` for dynamic port binding
- Compatible with Cloud Run and GKE dynamic port assignment

### 4. State Management & Session Issues (cr-java-0065, cr-java-0067)
**Problem**: HTTP session state storage and in-memory caching without TTL
**Solution**:
- Replaced HTTP session with Redis-backed distributed sessions
- Added Spring Session Data Redis dependency
- Created `RedisConfig` for Memorystore for Redis integration
- Replaced in-memory HashMap cache with Redis with TTL
- Updated `BookingController` to use Redis for all state storage

### 5. Security & Authentication (cr-java-0090)
**Problem**: File-based authentication
**Solution**:
- Integrated Google Secret Manager for credential storage
- Added documentation for retrieving secrets via Spring Cloud GCP
- Removed local file-based credential storage

### 6. Clock/Time Dependencies (cr-java-0111)
**Problem**: Local timezone dependencies and java.util.Timer usage
**Solution**:
- Standardized all timestamps to UTC
- Created `ScheduledTaskService` with Spring @Scheduled
- Configured for Cloud Scheduler integration
- Added timezone configuration in `application.properties`

## Required Environment Variables

### Database Configuration
- `DATABASE_URL` - Database connection URL
- `DATABASE_USERNAME` - Database username
- `DATABASE_PASSWORD` - Database password (use Secret Manager)
- `DATABASE_DRIVER` - JDBC driver class name

### Redis Configuration (Memorystore for Redis)
- `REDIS_HOST` - Redis host address
- `REDIS_PORT` - Redis port (default: 6379)
- `REDIS_PASSWORD` - Redis password (use Secret Manager)
- `REDIS_SSL` - Enable SSL for Redis connection

### Google Cloud Platform
- `GCP_PROJECT_ID` - GCP project ID
- `GCS_BUCKET_NAME` - Cloud Storage bucket name for reports
- `SECRET_MANAGER_ENABLED` - Enable Secret Manager integration

### Service Endpoints
- `PAYMENT_SERVICE_URL` - Payment service endpoint
- `INVENTORY_SERVICE_URL` - Inventory service endpoint
- `NOTIFICATION_SERVICE_URL` - Notification service endpoint

### Application Configuration
- `PORT` - Server port (dynamically assigned by Cloud Run/GKE)
- `SESSION_TIMEOUT` - Session timeout in seconds
- `REPORT_CRON` - Cron expression for report generation

## GCP Services Required

1. **Google Cloud Storage** - For file storage and report generation
2. **Google Secret Manager** - For secure credential management
3. **Memorystore for Redis** - For distributed session management and caching
4. **Cloud SQL** (optional) - For production database
5. **Cloud Scheduler** - For scheduled task execution
6. **Cloud Run or GKE** - For application deployment

## Deployment Instructions

### 1. Set up GCP Resources
```bash
# Create GCS bucket
gsutil mb gs://resortslite-reports

# Create Memorystore for Redis instance
gcloud redis instances create resortslite-cache \
  --size=1 \
  --region=us-central1 \
  --redis-version=redis_6_x

# Create secrets in Secret Manager
echo -n "your-db-password" | gcloud secrets create db-password --data-file=-
echo -n "your-redis-password" | gcloud secrets create redis-password --data-file=-
```

### 2. Configure Environment Variables
Create a `.env` file or configure in Cloud Run/GKE:
```properties
GCP_PROJECT_ID=your-project-id
GCS_BUCKET_NAME=resortslite-reports
REDIS_HOST=10.0.0.3
REDIS_PORT=6379
DATABASE_URL=jdbc:postgresql://localhost:5432/resortdb
DATABASE_USERNAME=resortuser
```

### 3. Build and Deploy
```bash
# Build the application
mvn clean package

# Deploy to Cloud Run (example)
gcloud run deploy resortslite \
  --image gcr.io/${GCP_PROJECT_ID}/resortslite:latest \
  --platform managed \
  --region us-central1 \
  --allow-unauthenticated \
  --set-env-vars GCP_PROJECT_ID=${GCP_PROJECT_ID},GCS_BUCKET_NAME=resortslite-reports
```

## Architecture Changes

### Before (Cloud-Incompatible)
- Local file system for reports
- HTTP session state in memory
- In-memory HashMap cache
- Hardcoded credentials in code
- Fixed port numbers
- Local timezone dependencies

### After (Cloud-Native)
- Google Cloud Storage for all file operations
- Redis-backed distributed sessions (Memorystore)
- Redis cache with TTL
- Secret Manager for credentials
- Dynamic port binding
- UTC timezone standardization
- Stateless application design

## Testing

### Local Development
1. Start Redis locally: `docker run -p 6379:6379 redis:6-alpine`
2. Set environment variables in IDE or `.env` file
3. Run application: `mvn spring-boot:run`

### Cloud Environment
1. Ensure all GCP services are provisioned
2. Configure environment variables in Cloud Run/GKE
3. Deploy application
4. Verify health endpoints

## Monitoring and Observability

The application is now compatible with:
- Google Cloud Logging (structured logging)
- Google Cloud Monitoring (metrics)
- Google Cloud Trace (distributed tracing)
- Google Cloud Profiler (performance profiling)

## Security Considerations

1. All credentials are externalized to Secret Manager
2. Database connections use HikariCP connection pooling
3. Redis connections support SSL/TLS
4. No sensitive data in source code or container images
5. Follows 12-factor app principles

## Compliance

This application now meets:
- GCP Well-Architected Framework requirements
- Cloud-native application standards
- Horizontal scaling requirements
- Container orchestration compatibility
- Security best practices for cloud deployment
