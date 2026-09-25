# ResortsLite — Deployment Guide

## Overview

This guide covers building, containerizing, and deploying the **ResortsLite** Spring Boot application (Java 8) to **Azure Kubernetes Service (AKS)**.

- **Framework**: Spring Boot 2.7.18
- **Java Version**: 8
- **Build Tool**: Maven
- **Application Port**: 8080
- **Health Endpoint**: `/actuator/health`
- **External Dependencies**: Azure Cache for Redis (session store + distributed cache)

---

## Prerequisites

### Local Development
| Tool | Version | Purpose |
|------|---------|---------|
| Java JDK | 8+ | Build and run locally |
| Maven | 3.8+ | Build tool |
| Docker | 20.10+ | Container build and run |
| Docker Compose | 2.x | Local multi-container orchestration |

### Azure AKS Deployment
| Tool | Version | Purpose |
|------|---------|---------|
| Azure CLI | 2.50+ | Azure resource management |
| kubectl | 1.27+ | Kubernetes cluster management |
| Azure Subscription | — | AKS cluster and ACR hosting |

---

## Project Structure

```
Comp1/
├── Dockerfile                  # Multi-stage Docker build
├── docker-compose.yml          # Local development compose file
├── .dockerignore               # Docker build exclusions
├── pom.xml                     # Maven build descriptor
├── src/
│   └── main/
│       ├── java/com/demo/resortslite/
│       │   ├── ResortsLiteApplication.java
│       │   ├── BookingController.java
│       │   ├── BookingService.java
│       │   ├── ReportService.java
│       │   └── RedisConfig.java
│       └── resources/
│           └── application.properties
├── kubernetes/
│   ├── namespace.yaml
│   ├── deployment.yaml
│   ├── service.yaml
│   └── ingress.yaml
├── scripts/
│   ├── build-push.sh           # Linux/macOS build & push
│   ├── build-push.bat          # Windows build & push
│   ├── deploy-image.sh         # Linux/macOS AKS deploy
│   └── deploy-image.bat        # Windows AKS deploy
└── docs/
    └── DEPLOYMENT.md           # This file
```

---

## Environment Variables

The application requires the following environment variables at runtime:

| Variable | Description | Default |
|----------|-------------|---------|
| `REDIS_HOST` | Azure Cache for Redis hostname | `localhost` |
| `REDIS_PORT` | Redis port (6380 for Azure SSL) | `6379` |
| `REDIS_PASSWORD` | Redis access key | _(empty)_ |
| `REDIS_SSL` | Enable SSL for Redis | `true` |
| `PAYMENT_API_URL` | Payment service endpoint | `http://payment-svc.internal:9090/payments/charge` |
| `REPORT_BASE_PATH` | Base path for report files | `/var/reports` |
| `BACKUP_PATH` | Backup directory path | `/var/backups/nightly` |
| `SPRING_PROFILES_ACTIVE` | Active Spring profile | `docker` |
| `JAVA_OPTS` | JVM options | `-Xms256m -Xmx512m ...` |

---

## Local Development with Docker Compose

### 1. Configure Environment

Create a `.env` file in the project root:

```env
REDIS_HOST=<your-redis-host>
REDIS_PORT=6380
REDIS_PASSWORD=<your-redis-password>
REDIS_SSL=true
PAYMENT_API_URL=http://payment-svc.internal:9090/payments/charge
REPORT_BASE_PATH=/var/reports
BACKUP_PATH=/var/backups/nightly
```

### 2. Build and Start

```bash
# Build and start the application container
docker-compose up --build

# Run in background
docker-compose up -d --build

# View logs
docker-compose logs -f resortslite

# Stop
docker-compose down
```

### 3. Verify Application

```bash
# Health check
curl http://localhost:8080/actuator/health

# Test booking endpoint
curl -X POST "http://localhost:8080/api/bookings/create?guestName=John&roomType=STANDARD&checkIn=2024-01-15&checkOut=2024-01-20"
```

---

## Building and Pushing the Docker Image

### Linux / macOS

```bash
# Make script executable
chmod +x scripts/build-push.sh

# Run from project root
./scripts/build-push.sh
```

The script will prompt for:
1. Image tag (default: `latest`)
2. Registry type: Azure ACR or Docker Hub
3. Registry credentials

### Windows

```cmd
scripts\build-push.bat
```

### Manual Build

```bash
# Build image
docker build -t resortslite:latest .

# Tag for ACR
docker tag resortslite:latest <acr-name>.azurecr.io/resortslite:latest

# Login to ACR
az acr login --name <acr-name>

# Push
docker push <acr-name>.azurecr.io/resortslite:latest
```

---

## Azure AKS Deployment

### Step 1: Prerequisites Setup

```bash
# Login to Azure
az login

# Set subscription
az account set --subscription "<subscription-id>"

# Install kubectl (if not installed)
az aks install-cli
```

### Step 2: Create AKS Cluster (if not existing)

```bash
# Create resource group
az group create --name resortslite-rg --location eastus

# Create AKS cluster
az aks create \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --node-count 2 \
  --node-vm-size Standard_DS2_v2 \
  --enable-addons monitoring \
  --generate-ssh-keys

# Enable Application Gateway Ingress Controller (AGIC)
az aks enable-addons \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --addons ingress-appgw \
  --appgw-name resortslite-appgw \
  --appgw-subnet-cidr "10.225.0.0/16"
```

### Step 3: Create Azure Container Registry

```bash
# Create ACR
az acr create \
  --resource-group resortslite-rg \
  --name resortsliteacr \
  --sku Basic

# Attach ACR to AKS (allows AKS to pull images)
az aks update \
  --resource-group resortslite-rg \
  --name resortslite-aks \
  --attach-acr resortsliteacr
```

### Step 4: Build and Push Image to ACR

```bash
chmod +x scripts/build-push.sh
./scripts/build-push.sh
# Select: 1 (Azure ACR)
# ACR name: resortsliteacr
# Tag: 1.0.0
```

### Step 5: Deploy to AKS

#### Linux / macOS

```bash
chmod +x scripts/deploy-image.sh
./scripts/deploy-image.sh
```

#### Windows

```cmd
scripts\deploy-image.bat
```

The script will prompt for:
- Azure Resource Group
- AKS Cluster name
- Full Docker image URI (e.g., `resortsliteacr.azurecr.io/resortslite:1.0.0`)
- Redis connection details
- Other environment variables

### Step 6: Manual Deployment (Alternative)

```bash
# Get AKS credentials
az aks get-credentials --resource-group resortslite-rg --name resortslite-aks

# Apply manifests
kubectl apply -f kubernetes/namespace.yaml
kubectl apply -f kubernetes/deployment.yaml
kubectl apply -f kubernetes/service.yaml
kubectl apply -f kubernetes/ingress.yaml

# Wait for rollout
kubectl rollout status deployment/resortslite -n resortslite

# Verify
kubectl get pods,svc,ingress -n resortslite
```

---

## Kubernetes Manifest Descriptions

| File | Kind | Description |
|------|------|-------------|
| `namespace.yaml` | Namespace | Creates `resortslite` namespace |
| `deployment.yaml` | Deployment | 2 replicas, liveness/readiness probes on `/actuator/health` |
| `service.yaml` | Service | ClusterIP service, port 80 → 8080 |
| `ingress.yaml` | Ingress | Azure Application Gateway Ingress Controller |

### Resource Limits

```yaml
resources:
  requests:
    cpu: "250m"
    memory: "512Mi"
  limits:
    cpu: "500m"
    memory: "1Gi"
```

### Health Probes

```yaml
livenessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60   # Allow JVM startup time
  periodSeconds: 30

readinessProbe:
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 15
```

> **Note**: The `initialDelaySeconds: 60` for liveness accounts for JVM startup time on Java 8.

---

## Scaling and Management

### Horizontal Pod Autoscaling

```bash
# Create HPA (scale between 2-10 pods based on CPU)
kubectl autoscale deployment resortslite \
  --namespace resortslite \
  --cpu-percent=70 \
  --min=2 \
  --max=10

# Check HPA status
kubectl get hpa -n resortslite
```

### Rolling Updates

```bash
# Update image
kubectl set image deployment/resortslite \
  resortslite=resortsliteacr.azurecr.io/resortslite:2.0.0 \
  -n resortslite

# Monitor rollout
kubectl rollout status deployment/resortslite -n resortslite
```

### Rollback

```bash
# Rollback to previous version
kubectl rollout undo deployment/resortslite -n resortslite

# Rollback to specific revision
kubectl rollout history deployment/resortslite -n resortslite
kubectl rollout undo deployment/resortslite --to-revision=2 -n resortslite
```

---

## Troubleshooting

### Pod Not Starting

```bash
# Check pod status
kubectl get pods -n resortslite

# Describe pod for events
kubectl describe pod <pod-name> -n resortslite

# View pod logs
kubectl logs <pod-name> -n resortslite

# View previous container logs (if crashed)
kubectl logs <pod-name> -n resortslite --previous
```

### Common Issues

#### 1. Redis Connection Failure
```
Error: Unable to connect to Redis
```
**Solution**: Verify `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` environment variables are correctly set. For Azure Cache for Redis, use port `6380` with SSL enabled.

```bash
# Check environment variables in pod
kubectl exec -it <pod-name> -n resortslite -- env | grep REDIS
```

#### 2. OOMKilled (Out of Memory)
```
State: OOMKilled
```
**Solution**: Increase memory limits in `deployment.yaml` or tune JVM heap:
```yaml
- name: JAVA_OPTS
  value: "-Xms256m -Xmx768m -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"
```

#### 3. Liveness Probe Failing
```
Liveness probe failed: HTTP probe failed with statuscode: 503
```
**Solution**: Increase `initialDelaySeconds` to allow more JVM startup time, or check Redis connectivity (Spring Boot health includes Redis health by default).

#### 4. Image Pull Error
```
ErrImagePull / ImagePullBackOff
```
**Solution**: Ensure ACR is attached to AKS cluster:
```bash
az aks update --resource-group <rg> --name <aks> --attach-acr <acr-name>
```

#### 5. Ingress Not Accessible
```bash
# Check ingress status
kubectl describe ingress resortslite-ingress -n resortslite

# Check Application Gateway logs
kubectl logs -n kube-system -l app=ingress-appgw
```

---

## Security Considerations

1. **Redis Credentials**: Store `REDIS_PASSWORD` in Azure Key Vault and inject via Secrets Store CSI Driver — do NOT hardcode in manifests.

2. **Kubernetes Secrets**: For sensitive values, use Kubernetes Secrets:
   ```bash
   kubectl create secret generic resortslite-secrets \
     --namespace resortslite \
     --from-literal=REDIS_PASSWORD=<password>
   ```
   Then reference in deployment:
   ```yaml
   - name: REDIS_PASSWORD
     valueFrom:
       secretKeyRef:
         name: resortslite-secrets
         key: REDIS_PASSWORD
   ```

3. **Non-Root Container**: The Dockerfile runs as a non-root user (`appuser`) for security.

4. **Network Policies**: Consider adding Kubernetes NetworkPolicies to restrict pod-to-pod communication.

5. **Image Scanning**: Enable Azure Defender for Containers to scan ACR images for vulnerabilities.

---

## Java-Specific Notes

### JVM Container Awareness
The application uses `-XX:+UseContainerSupport` and `-XX:MaxRAMPercentage=75.0` to ensure the JVM respects container memory limits rather than using host memory values.

### Spring Boot Actuator
Health endpoint is exposed at `/actuator/health` (configured in `application.properties`):
```properties
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=always
```

### Spring Session with Redis
The application uses Spring Session backed by Azure Cache for Redis for distributed session management across AKS pod replicas. This ensures session state is preserved during pod restarts and horizontal scaling.

### H2 In-Memory Database
The application uses H2 in-memory database for development. For production, replace with a persistent database (Azure SQL, PostgreSQL) and update `spring.datasource.*` properties accordingly.

---

## Useful Commands Reference

```bash
# Get all resources in namespace
kubectl get all -n resortslite

# Port-forward for local testing
kubectl port-forward svc/resortslite-service 8080:80 -n resortslite

# Execute shell in pod
kubectl exec -it <pod-name> -n resortslite -- /bin/sh

# View resource usage
kubectl top pods -n resortslite

# Delete all resources
kubectl delete namespace resortslite
```
