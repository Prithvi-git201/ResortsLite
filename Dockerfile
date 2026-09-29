# =============================================================================
# Multi-stage Dockerfile for ResortsLite Spring Boot Application
# Java 8 / Spring Boot 2.7.x / Maven
# Runtime base image: amazoncorretto:8 (explicit)
# =============================================================================

# ---------------------------------------------------------------------------
# Stage 1: Builder
# ---------------------------------------------------------------------------
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy Maven build descriptor first for dependency layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy application source code
COPY src ./src

# Build the application JAR (skip tests for Docker build)
RUN mvn clean package -DskipTests -B

# ---------------------------------------------------------------------------
# Stage 2: Runtime
# ---------------------------------------------------------------------------
FROM amazoncorretto:8

# Metadata labels
LABEL maintainer="ResortsLite Team" \
      application="resortslite" \
      version="1.0.0" \
      description="ResortsLite Spring Boot Application"

# Set timezone
ENV TZ=UTC

# JVM memory and container-awareness settings
ENV JAVA_OPTS="-Xmx512m -Xms256m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+ExitOnOutOfMemoryError \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile for containerised deployment
ENV SPRING_PROFILES_ACTIVE=docker

# Application port
ENV SERVER_PORT=8080

# Memcached endpoint (override via ECS task definition / SSM Parameter Store)
ENV MEMCACHED_ENDPOINT=localhost:11211
ENV MEMCACHED_EXPIRY_SECS=3600

# JWT secret (override via ECS task definition / AWS Secrets Manager)
ENV JWT_SECRET=default-dev-secret-change-in-production

# ALB stickiness settings
ENV ALB_STICKINESS_ENABLED=true
ENV ALB_COOKIE_DURATION_SECS=86400

# EFS-backed mount paths (override via ECS task definition)
ENV REPORT_BASE_PATH=/mnt/efs/reports
ENV BACKUP_PATH=/mnt/efs/backups

# Create a non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -d /app -s /sbin/nologin appuser

# Create application directories
RUN mkdir -p /app/logs /mnt/efs/reports /mnt/efs/backups \
    && chown -R appuser:appgroup /app /mnt/efs

WORKDIR /app

# Copy the built JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown appuser:appgroup app.jar

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Graceful shutdown support (Spring Boot handles SIGTERM)
STOPSIGNAL SIGTERM

# Run the application
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
