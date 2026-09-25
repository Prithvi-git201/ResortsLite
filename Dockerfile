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
      application="resortsLite" \
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

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application environment variables (override at runtime via ECS task definition)
ENV SERVER_PORT=8080
ENV JWT_SECRET=default-dev-secret-change-in-production-min32chars
ENV MEMCACHED_ENDPOINT=localhost:11211
ENV PAYMENT_SERVICE_URL=http://payment-service:9090/payments/charge
ENV REPORT_BASE_PATH=/mnt/efs/reports

# Create non-root user for security
RUN groupadd -r appgroup && useradd -r -g appgroup -d /app -s /sbin/nologin appuser

# Create application directories
RUN mkdir -p /app /mnt/efs/reports /app/logs \
    && chown -R appuser:appgroup /app /mnt/efs/reports

WORKDIR /app

# Copy the built JAR from builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Set ownership
RUN chown appuser:appgroup app.jar

# Switch to non-root user
USER appuser

# Expose application port
EXPOSE 8080

# Use exec form for proper signal handling (graceful shutdown)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
