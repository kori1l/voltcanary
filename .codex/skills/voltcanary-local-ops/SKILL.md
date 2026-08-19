---
name: voltcanary-local-ops
description: VoltCanary local operations workflow for Docker Compose, local Kubernetes, Helm values, ports, environment variables, health checks, observability, smoke scripts, and diagnosing local deployment/runtime failures. Use when Codex runs, changes, or reviews deploy/, docker-compose.yml, scripts/, or local infrastructure docs.
---

# VoltCanary Local Ops

## Before Editing

Use `$voltcanary-openspec` first for deployment, security, environment,
runtime, persistence, scaling, or cross-service topology changes.

Read these sources before changing local operations behavior:

- `README.md`
- `.env.example`
- `docker-compose.yml`
- `docs/k8s-local.md`
- `docs/architecture/06-kubernetes-deployment.md`
- `docs/architecture/07-observability.md`
- `deploy/k8s/`, `deploy/helm/`, `scripts/`

## Compose

Use profiles intentionally:

```powershell
docker compose --profile infra up -d
docker compose --profile observability up -d
docker compose --profile app up
```

Default local ports:

- Frontend: `3000`
- API Gateway: `8080`
- Java services: `8081` through `8086`
- ML service: `8090`
- PostgreSQL: `5432`
- TimescaleDB: `5433`
- Redpanda: `9092`
- Redis: `6379`
- MinIO: `9000`, console `9001`
- Prometheus: `9090`
- Grafana: `3001`
- Loki: `3100`

## Kubernetes

For Docker Desktop Kubernetes:

```powershell
kubectl apply -k deploy/k8s/overlays/local
kubectl get deploy,hpa,pdb,pods -n voltcanary
kubectl -n voltcanary port-forward svc/frontend 3000:3000
kubectl -n voltcanary port-forward svc/api-gateway 8080:8080
```

Keep local manifests aligned with `docs/k8s-local.md` and Helm values when a
behavior is shared.

## Diagnostics

Check health endpoints before deeper debugging:

- Gateway: `http://localhost:8080/actuator/health`
- ML service: `http://localhost:8090/health`
- Frontend dev server: `http://localhost:3000`

Use Grafana Explore with the Loki datasource for Compose logs. Query all local
Compose logs with `{compose_project="voltcanary"}` or a single service with
`{compose_service="<service-name>"}`.

## ML Docker Smoke

Use `scripts/ml-failure-prediction-docker-smoke.ps1` as the primary local
Docker verification for the ML failure-prediction pipeline:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ml-failure-prediction-docker-smoke.ps1 -StartStack
```

When the stack is already running, omit `-StartStack`. Add
`-VerifyLifecycleCleanup` when the request asks to verify create/delete/archive
or lifecycle-impact behavior; that mode checks blocked destructive actions,
manual label delete, policy disable/delete, and archive paths for referenced ML
lineage records. Use `-CiMode -TimeoutSeconds 600 -SummaryPath artifacts/ml-production-smoke-summary.json`
for the CI-safe gate or release evidence run.

Never add secrets to tracked files. Put local values in `.env` and document
new required keys in `.env.example`.
