# Local Kubernetes

This document describes the current local Kubernetes setup used for VoltCanary
on Docker Desktop.

The local manifests live under `deploy/k8s`. The active entrypoint is:

```powershell
kubectl apply -k deploy/k8s/overlays/local
```

The local overlay is intentionally development-oriented. Java, Python, and
frontend pods use official `maven`, `python`, and `node` images and mount the
local source tree from the Windows host. This keeps the workflow close to the
current Docker Compose setup and avoids building production images for every
service during early development.

## Prerequisites

- Docker Desktop with Kubernetes enabled, or a compatible local cluster.
- `kubectl` configured for the target cluster.
- The repository checked out at `D:\canary\voltcanary`.

For Docker Desktop on Windows, this repository path is mounted inside the
Kubernetes VM as:

```text
/run/desktop/mnt/host/d/canary/voltcanary
```

If the repository is located elsewhere, update the `hostPath.path` values in:

```text
deploy/k8s/overlays/local/kustomization.yaml
```

## Start The Stack

```powershell
kubectl apply -k deploy/k8s/overlays/local
kubectl get pods -n voltcanary -w
```

The first startup can take several minutes. Java pods run Maven inside the
container, download dependencies, build the target service, and then start
Spring Boot.

## Current Workloads

The application namespace is `voltcanary`.

Stateless workloads run as Kubernetes `Deployment` resources:

| Workload | Replicas | Autoscaling |
| --- | ---: | --- |
| `frontend` | 2 | HPA 2-4 pods, CPU target 70% |
| `api-gateway` | 2 | HPA 2-4 pods, CPU target 70% |
| `identity-service` | 2 | HPA 2-4 pods, CPU target 70% |
| `device-service` | 2 | HPA 2-4 pods, CPU target 70% |
| `ingestion-service` | 2 | HPA 2-4 pods, CPU target 70% |
| `telemetry-service` | 2 | HPA 2-4 pods, CPU target 70% |
| `analytics-service` | 2 | HPA 2-4 pods, CPU target 70% |
| `alert-service` | 2 | HPA 2-4 pods, CPU target 70% |
| `simulator-service` | 2 | HPA 2-4 pods, CPU target 70% |
| `ai-agent-service` | 2 | HPA 2-4 pods, CPU target 70% |
| `ml-service` | 2 | HPA 2-4 pods, CPU target 70% |

Stateful infrastructure currently runs as single-replica `StatefulSet`
resources:

| Workload | Role |
| --- | --- |
| `postgres` | business data |
| `timescaledb` | telemetry time-series data |
| `redis` | cache and latest values |
| `redpanda` | Kafka-compatible event broker |
| `minio` | object storage for uploads, model artifacts, reports, and exports |

The local setup also includes one-time initialization jobs:

- `redpanda-init`: creates Kafka topics.
- `minio-init`: creates required MinIO buckets, including `model-artifacts`
  for ML-service model and feature artifacts.

## High Availability Settings

The local overlay adds production-like controls for stateless services:

- `replicas: 2`
- `HorizontalPodAutoscaler` with `minReplicas: 2`, `maxReplicas: 4`
- `PodDisruptionBudget` with `maxUnavailable: 1`
- resource requests and limits
- rolling updates with `maxUnavailable: 1`, `maxSurge: 0`
- topology spread constraints by node hostname

This improves pod-level resilience, rolling restart behavior, and autoscaling
behavior. It does not provide full production high availability on Docker
Desktop because the local cluster has one node. If that node fails, all pods
fail with it.

Stateful services are not simply scaled to multiple replicas. PostgreSQL,
TimescaleDB, Redis, MinIO, and Redpanda need their own clustered configuration
or operators to become truly highly available.

## Port Forwarding

The Kubernetes services are internal `ClusterIP` services. Use port-forwarding
for local browser access.

Frontend:

```powershell
kubectl -n voltcanary port-forward svc/frontend 3000:3000
```

API Gateway:

```powershell
kubectl -n voltcanary port-forward svc/api-gateway 8080:8080
```

MinIO console:

```powershell
kubectl -n voltcanary port-forward svc/minio 9001:9001
```

Redpanda admin:

```powershell
kubectl -n voltcanary port-forward svc/redpanda 9644:9644
```

## Health Checks

```powershell
kubectl get deploy,hpa,pdb,pods -n voltcanary
kubectl top nodes
kubectl top pods -n voltcanary
```

From the host after port-forwarding:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/actuator/health
Invoke-WebRequest -UseBasicParsing http://localhost:3000/index.html
```

ML-service readiness uses `GET /health/artifacts`. The local Kubernetes overlay
sets `ML_RUNTIME_PROFILE=kubernetes` and `ML_ARTIFACT_STORE=s3`, so readiness
fails until MinIO is reachable and the `model-artifacts` bucket can be checked.

## Useful Commands

```powershell
kubectl get all -n voltcanary
kubectl logs -n voltcanary deploy/api-gateway -f
kubectl logs -n voltcanary deploy/identity-service -f
kubectl describe pod -n voltcanary <pod-name>
```

Restart one app:

```powershell
kubectl rollout restart deployment/frontend -n voltcanary
kubectl rollout restart deployment/api-gateway -n voltcanary
```

Scale a stateless service manually:

```powershell
kubectl scale deployment api-gateway -n voltcanary --replicas=3
```

If HPA is enabled, it may later move the deployment back inside its configured
range.

Delete the local stack:

```powershell
kubectl delete -k deploy/k8s/overlays/local
```

PVC data may remain if the cluster keeps persistent volumes. To fully reset
local data:

```powershell
kubectl delete pvc --all -n voltcanary
```

## Monitoring And Dashboard

Kubernetes Dashboard runs in the `kubernetes-dashboard` namespace.

```powershell
kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard-kong-proxy 8443:443
kubectl -n kubernetes-dashboard create token admin-user
```

Dashboard URL:

```text
https://localhost:8443
```

Grafana runs in the `monitoring` namespace.

```powershell
kubectl -n monitoring port-forward svc/monitoring-grafana 3001:80
```

Grafana URL:

```text
http://localhost:3001
```

Default local credentials:

```text
admin / admin
```

Logs are collected by Promtail and stored in Loki. In Grafana, open Explore,
select the Loki datasource, and query:

```logql
{namespace="voltcanary"}
```
