# Kubernetes Deployment

VoltCanary is packaged for Kubernetes through Kustomize manifests under
`deploy/k8s`.

The current local deployment is production-like for learning and validation,
but it is still a local development deployment. It uses Docker Desktop
Kubernetes, source-code `hostPath` mounts, and official language runtime images
instead of immutable service images.

## Layout

```text
deploy/k8s/
  base/
    namespace.yaml
    config.yaml
    init-configmaps.yaml
    infra.yaml
    redpanda-minio.yaml
    apps.yaml
    ml-frontend.yaml
    kustomization.yaml
  overlays/local/
    kustomization.yaml
    ha.yaml
  local/
    kustomization.yaml
```

The local overlay extends the base with Docker Desktop `hostPath` mounts and
high-availability controls for stateless services.

## Namespaces

| Namespace | Purpose |
| --- | --- |
| `voltcanary` | application workloads and local infrastructure |
| `monitoring` | Prometheus, Grafana, Loki, Promtail, Alertmanager |
| `kubernetes-dashboard` | Kubernetes Dashboard |
| `kube-system` | cluster system services such as CoreDNS and Metrics Server |

## Workload Types

Stateless services are deployed as `Deployment` resources:

- `frontend`
- `api-gateway`
- `identity-service`
- `device-service`
- `ingestion-service`
- `telemetry-service`
- `analytics-service`
- `alert-service`
- `ml-service`

Stateful infrastructure is deployed as `StatefulSet` resources:

- `postgres`
- `timescaledb`
- `redis`
- `redpanda`
- `minio`

Initialization tasks are deployed as `Job` resources:

- `redpanda-init`
- `minio-init`

## Service Discovery

Every workload exposes an internal `ClusterIP` service. Services communicate by
Kubernetes DNS names:

```text
http://identity-service:8081
http://device-service:8082
http://ingestion-service:8083
http://telemetry-service:8084
http://analytics-service:8085
http://alert-service:8086
http://ml-service:8090
redpanda:9092
postgres:5432
timescaledb:5432
redis:6379
minio:9000
```

Local browser access is provided through `kubectl port-forward`, not through
Ingress.

## Configuration

Application configuration is stored in:

- `voltcanary-config` ConfigMap
- `voltcanary-secrets` Secret

The local Secret uses development values. Production must replace these values
with externally managed secrets from a cloud secret manager, sealed secrets, or
another controlled secret distribution mechanism.

Maintenance-window rollout adds these operational settings:

| Variable | Consumers | Purpose |
| --- | --- | --- |
| `ALERT_MAINTENANCE_RECONCILIATION_ENABLED` | `alert-service` | Enables maintenance projection reconciliation. |
| `ALERT_MAINTENANCE_RECONCILIATION_INTERVAL` | `alert-service` | Delay between reconciliation scans. |
| `ALERT_MAINTENANCE_RECONCILIATION_LOCK_LEASE` | `alert-service` | Scheduler lock lease for one active reconciler. |
| `ALERT_MAINTENANCE_RECONCILIATION_BATCH_SIZE` | `alert-service` | Maximum device-service snapshots per fetch. |
| `ALERT_KAFKA_MAINTENANCE_WINDOW_DLQ_TOPIC` | `alert-service` | Dead-letter topic for failed maintenance projection messages. |
| `ANALYTICS_MAINTENANCE_RECONCILIATION_ENABLED` | `analytics-service` | Enables maintenance projection reconciliation. |
| `ANALYTICS_MAINTENANCE_RECONCILIATION_INTERVAL` | `analytics-service` | Delay between reconciliation scans. |
| `ANALYTICS_MAINTENANCE_RECONCILIATION_LOCK_LEASE` | `analytics-service` | Scheduler lock lease for one active reconciler. |
| `ANALYTICS_MAINTENANCE_RECONCILIATION_BATCH_SIZE` | `analytics-service` | Maximum device-service snapshots per fetch. |

The shared `DEVICE_SERVICE_URI` and `INTERNAL_SERVICE_TOKEN` must be configured
for analytics-service and alert-service reconciliation calls.

ML-service also uses the shared internal boundary and durable artifact storage
policy. `voltcanary-config` sets `ML_RUNTIME_PROFILE=kubernetes`,
`ML_ARTIFACT_STORE=s3`, `ML_INTERNAL_AUTH_REQUIRED=true`,
`ML_ALLOWED_SOURCE_SERVICES`, and `ML_MAX_REQUEST_BYTES`; `voltcanary-secrets`
provides both
`INTERNAL_SERVICE_TOKEN` for analytics-service and `ML_INTERNAL_SERVICE_TOKEN`
for ML-service. `GET /health` and `GET /health/artifacts` remain open for
probes, while `/inference`, `/train`, `/runs/{runId}`, `/models`, and `/ml/*`
require the configured headers. If MinIO/S3 is unavailable or ML-service is
misconfigured for local artifact storage, `/health/artifacts` returns `503` and
the pod is not ready.

## Event Topics

Redpanda initialization creates application topics including:

```text
telemetry.raw.v1
telemetry.validated.v1
device-registry.changed.v1
maintenance-window.changed.v1
source.runtime-config.v1
```

Alert-service can dead-letter failed projection messages to:

```text
maintenance-window.changed.dlq.v1
```

The topic is keyed by `deviceId` so consumers preserve per-device maintenance
ordering as much as the broker partitioning allows.

`source.runtime-config.v1` is compacted and keyed by `dataSourceId`. Ingestion
replicas consume it with replica-specific groups so every pod rebuilds its local
source read model after startup or restart.

## Scaling Model

The current local overlay configures stateless services with:

- `replicas: 2`
- `HorizontalPodAutoscaler` from 2 to 4 replicas
- CPU target utilization of 70%
- resource requests and limits
- `PodDisruptionBudget` with `maxUnavailable: 1`
- rolling updates with `maxUnavailable: 1` and `maxSurge: 0`
- topology spread constraints across node hostnames

This gives pod-level resilience and validates Kubernetes autoscaling behavior.
On a real multi-node cluster, topology spread constraints help distribute pods
across nodes. On Docker Desktop, all pods still run on one node.

`ingestion-service` horizontal operation requires the Redis-backed
idempotency/rate-limit flags in `voltcanary-config`, compacted
`source.runtime-config.v1`, and Redis connector leases. Run
`scripts/ingestion-horizontal-scale-smoke.ps1` against the target environment
before treating the HA overlay as an ingestion correctness gate.

## Stateful Services

Stateful services remain single-replica in the local deployment.

They must not be scaled by only changing `replicas`, because each component
needs a data-aware clustering model:

| Component | Production HA direction |
| --- | --- |
| PostgreSQL | managed PostgreSQL, Patroni, CloudNativePG, or another Postgres operator |
| TimescaleDB | managed Timescale/PostgreSQL or a Postgres HA operator with Timescale support |
| Redis | Redis Sentinel, Redis Cluster, or a managed Redis service |
| Redpanda | Redpanda cluster with multiple brokers, correct advertised listeners, and replicated topics |
| MinIO | distributed MinIO or managed object storage |

## Rollouts

Deploy local manifests:

```powershell
kubectl apply -k deploy/k8s/overlays/local
```

Check status:

```powershell
kubectl get deploy,hpa,pdb,pods -n voltcanary
kubectl rollout status deployment/api-gateway -n voltcanary
```

Restart a service:

```powershell
kubectl rollout restart deployment/api-gateway -n voltcanary
```

Scale a stateless service manually:

```powershell
kubectl scale deployment api-gateway -n voltcanary --replicas=3
```

If HPA is active, it remains the long-term controller for replica counts.

## Production Gaps

Before using this deployment model for a real environment, the following gaps
must be closed:

- build immutable images for every service
- publish images to a registry
- replace `hostPath` mounts with container image contents
- use external secret management
- add Ingress or Gateway API with TLS
- configure stateful HA or use managed stateful services
- configure persistent volume classes and backup policies
- add network policies
- add service monitors and application dashboards
- define alert rules and runbooks
