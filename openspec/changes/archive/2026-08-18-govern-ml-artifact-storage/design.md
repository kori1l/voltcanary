# Govern ML Artifact Storage Design

## Storage Modes

Supported modes should be explicit:

- `local`: only for local development and tests;
- `s3`: required for production-like Docker and Kubernetes deployments.

ML-service should know its runtime profile or receive an explicit setting that
forbids local storage in production. If a production-like profile starts with
`ML_ARTIFACT_STORE=local`, readiness should fail with a clear operator message.

## Source Control Policy

Generated files under `ml-service/models/` should not be the production source
of truth. The repo should keep only fixtures intentionally required by tests,
preferably under test fixture paths. New training runs must write to temp
directories in tests or to object storage in runtime profiles.

The cleanup should avoid deleting user changes blindly. Implementation should
separate fixture migration from generated artifact removal and document any
manual cleanup if needed.

## Artifact Lifecycle

Artifact cleanup must check references before deletion:

- model versions;
- training runs;
- deployment policies;
- prediction traceability;
- audit events where relevant.

The first implementation can expose a dry-run report or operations script
instead of immediate deletion. Destructive deletion should require an explicit
operator action and should never remove active or historically referenced model
artifacts.

## Readiness

`/health/artifacts` should verify:

- selected provider;
- bucket existence/access for S3;
- credentials;
- production storage mode policy.

The endpoint should not expose secrets and should return enough information for
operators to distinguish wrong provider, missing bucket, and credential errors.

## Verification

Tests should cover:

- local mode allowed in test profile;
- local mode rejected in production-like profile;
- S3 health with available and unavailable buckets;
- generated artifact paths ignored or relocated;
- cleanup dry-run blocks referenced artifacts.
