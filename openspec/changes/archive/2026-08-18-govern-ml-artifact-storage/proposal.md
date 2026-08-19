# Govern ML Artifact Storage

## Why

Production and Docker profiles are configured for S3/MinIO, but ML-service
still defaults to local artifact storage and the repository contains generated
model files under `ml-service/models`. That is acceptable as test/demo residue
but not as a production storage boundary.

Model artifacts need governance: production must use durable object storage,
source control must not become a model registry, and cleanup must respect model,
training, deployment, and prediction references.

## What Changes

- Make production artifact storage explicitly require S3-compatible object
  storage.
- Keep local artifact storage only for local development and unit tests.
- Prevent generated model and feature artifacts from being committed as normal
  source artifacts.
- Add artifact lifecycle/retention rules that preserve referenced artifacts and
  identify unreferenced cleanup candidates.
- Extend readiness/operations docs to diagnose wrong storage mode and missing
  buckets/credentials.

## Impact

Affected specs:

- `ml-artifact-runtime`

Affected implementation areas:

- `ml-service/app/config/settings.py`
- `ml-service/app/services/artifact_store.py`
- repository ignore/cleanup policy for `ml-service/models/`
- `deploy/k8s/base/config.yaml`
- Docker/MinIO setup scripts
- `docs/operations/ml-operations.md`
- `docs/api/ml-api.md`

Compatibility:

- Local unit tests can still use temp directories and local artifact store.
- Existing model registry entries remain readable if their artifacts still
  exist in the configured store.
