# =============================================================================
# ResortsLite - Multi-Stage Dockerfile
# Framework : Spring Boot 2.7.18
# Java      : 8 (eclipse-temurin)
# Build Tool: Maven
# =============================================================================

# -----------------------------------------------------------------------------
# Stage 1: Builder
# -----------------------------------------------------------------------------
FROM maven:3.9.4-eclipse-temurin-8 AS builder

WORKDIR /workspace

# Copy build descriptor first for dependency layer caching
COPY pom.xml .

# Download all dependencies (cached layer unless pom.xml changes)
RUN mvn dependency:go-offline -B

# Copy the full source tree
COPY src ./src

# Build the fat JAR (skip tests — tests run in CI pipeline)
RUN mvn clean package -DskipTests -B

# -----------------------------------------------------------------------------
# Stage 2: Runtime
# -----------------------------------------------------------------------------
FROM eclipse-temurin:8-jre

# Metadata
LABEL maintainer="ResortsLite Team" \
      application="resortsLite" \
      version="1.0.0" \
      description="Legacy resort booking application — Spring Boot 2.7.x / Java 8"

# Timezone
ENV TZ=UTC

# JVM tuning — container-aware heap sizing
ENV JAVA_OPTS="-Xms256m -Xmx512m \
  -XX:+UseContainerSupport \
  -XX:MaxRAMPercentage=75.0 \
  -XX:+UseG1GC \
  -Djava.security.egd=file:/dev/./urandom \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=UTC"

# Spring profile
ENV SPRING_PROFILES_ACTIVE=docker

# Application environment variables (overridden at runtime via ECS task definition)
ENV SERVER_PORT=8080
ENV JWT_SECRET=changeme-local-dev-secret-256bit!!
ENV MEMCACHED_ENDPOINT=localhost:11211
ENV PAYMENT_API_URL=http://payment-service:9090/payments/charge
ENV REPORT_BASE_PATH=/mnt/efs/reports/
ENV BACKUP_PATH=/mnt/efs/backups/nightly/

# Create non-root user for security
RUN groupadd --system appgroup && \
    useradd --system --gid appgroup --no-create-home appuser

# Application directories
RUN mkdir -p /app /mnt/efs/reports /mnt/efs/backups/nightly /app/logs && \
    chown -R appuser:appgroup /app /mnt/efs

WORKDIR /app

# Copy the fat JAR from the builder stage
COPY --from=builder /workspace/target/*.jar app.jar

# Ensure the JAR is owned by the non-root user
RUN chown appuser:appgroup app.jar

USER appuser

EXPOSE 8080

# Graceful shutdown — Spring Boot honours SIGTERM
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
