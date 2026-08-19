# Harden ML Service Internal Boundary Design

## Boundary

ML-service should distinguish:

- probe endpoints: `/health`, `/health/artifacts`;
- internal read endpoints: `/models`;
- internal execution endpoints: `/train`, `/runs/{id}`, `/inference`;
- compatibility aliases under `/ml/*`.

Production-like profiles should require an internal service token on every
non-probe endpoint. The token source should be ML-service configuration, not a
hardcoded value. Requests should also include `X-Source-Service` so logs and
metrics can attribute callers.

## Request Safety

`/train` can carry feature rows and therefore needs an upper body limit. The
limit should be configurable and low enough to prevent accidental gateway or
worker memory pressure. Oversized payloads should return `413` before parsing
or fitting.

Validation failures should keep the existing `400` compatibility behavior but
must avoid raw exception details, stack traces, S3 paths beyond the provided
contract, and secrets.

## Configuration

Suggested settings:

- `ML_INTERNAL_AUTH_REQUIRED`: default `true` outside tests/local.
- `ML_INTERNAL_SERVICE_TOKEN`: required when auth is enabled.
- `ML_MAX_REQUEST_BYTES`: maximum request body size for execution endpoints.
- `ML_ALLOWED_SOURCE_SERVICES`: optional allowlist, initially including
  `analytics-service`.

Kubernetes and Docker profiles should set the token through secrets. Unit tests
can override settings with auth disabled or with a test token.

## Failure Modes

- Missing/invalid token: return `401` or `403`, do not call training or
  inference code, increment auth failure metrics.
- Oversized payload: return `413`, do not parse or persist anything.
- Missing source service: return `403` when an allowlist is configured.
- Health probes: continue to work without credentials.

## Verification

Add FastAPI tests for allowed and denied requests, body limits, probe
compatibility, and `/ml/*` aliases. Add one analytics-service integration test
or existing `MlClient` test proving the configured headers are still sent.
