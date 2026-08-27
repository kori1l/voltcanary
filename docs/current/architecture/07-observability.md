# Observability

VoltCanary uses Prometheus, Grafana, Loki, Promtail, and Kubernetes metrics to
observe the local Kubernetes deployment.

## Goals

Observability should answer these questions:

- Are all services running and ready?
- Is a rollout stuck?
- Which pods are consuming CPU or memory?
- Are frontend requests reaching the API Gateway?
- Are API Gateway calls reaching backend services?
- Are Kafka consumers assigned to partitions?
- Are alerts and analytics events being produced?
- Where can logs for a specific service or namespace be inspected?

## Components

| Component | Namespace | Responsibility |
| --- | --- | --- |
| Prometheus | `monitoring` | collects and stores metrics |
| Grafana | `monitoring` | dashboards and Explore UI |
| Alertmanager | `monitoring` | receives Prometheus alerts |
| kube-state-metrics | `monitoring` | exposes Kubernetes object state |
| node-exporter | `monitoring` | exposes node metrics |
| Metrics Server | `kube-system` | provides metrics for `kubectl top` and HPA |
| Loki | `monitoring` | stores logs |
| Promtail | `monitoring` | ships pod logs to Loki |
| Kubernetes Dashboard | `kubernetes-dashboard` | Kubernetes web UI |

## Metrics

Metrics Server is required for:

- `kubectl top nodes`
- `kubectl top pods`
- CPU-based HorizontalPodAutoscaler decisions

Useful commands:

```powershell
kubectl top nodes
kubectl top pods -n voltcanary
kubectl get hpa -n voltcanary
```

Prometheus is installed through `kube-prometheus-stack`. It collects cluster
metrics, Kubernetes object metrics, node metrics, and metrics from the
monitoring stack.

## Logs

Application logs are written to stdout/stderr by the containers. Promtail
collects pod logs and sends them to Loki.

In Grafana:

1. Open `http://localhost:3001`.
2. Go to Explore.
3. Select the `Loki` datasource.
4. Query all application logs:

```logql
{namespace="voltcanary"}
```

Query one service:

```logql
{namespace="voltcanary", app="api-gateway"}
```

Query monitoring logs:

```logql
{namespace="monitoring"}
```

## Kubernetes Dashboard

Dashboard URL after port-forwarding:

```text
https://localhost:8443
```

Start port-forwarding:

```powershell
kubectl -n kubernetes-dashboard port-forward svc/kubernetes-dashboard-kong-proxy 8443:443
```

Generate a login token:

```powershell
kubectl -n kubernetes-dashboard create token admin-user
```

The local `admin-user` service account is intended only for local development.
It has broad permissions and must not be copied to production.

## Grafana

Grafana URL after port-forwarding:

```text
http://localhost:3001
```

Start port-forwarding:

```powershell
kubectl -n monitoring port-forward svc/monitoring-grafana 3001:80
```

Default local credentials:

```text
admin / admin
```

Configured datasources:

- Prometheus
- Loki

## Application Health

Java services expose Spring Boot Actuator health endpoints:

```text
/actuator/health
/actuator/health/readiness
/actuator/health/liveness
```

ML service exposes:

```text
/health
```

Frontend readiness checks use:

```text
/index.html
```

## Rollout Diagnostics

Common commands:

```powershell
kubectl get deploy,pods -n voltcanary
kubectl describe pod -n voltcanary <pod-name>
kubectl logs -n voltcanary deploy/api-gateway --tail=100
kubectl get events -n voltcanary --sort-by=.lastTimestamp
```

If a local rollout appears stuck, check:

- startup probe failures while Maven downloads dependencies
- memory pressure on Docker Desktop
- missing CPU requests for HPA
- broken port-forward sessions after pods are replaced

## Current Limitations

- Distributed tracing is not configured yet.
- Application-specific Grafana dashboards are still minimal.
- Prometheus alert rules and runbooks still need to be formalized.
- Logs are collected locally; retention and object storage sizing are not
  production-tuned.
