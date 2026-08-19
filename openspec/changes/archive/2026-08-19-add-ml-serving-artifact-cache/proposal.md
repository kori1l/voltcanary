# Add ML Serving Artifact Cache

## Why

Trained-model inference currently loads and verifies the selected model bundle
from artifact storage during prediction. That is correct for integrity but too
expensive for production traffic: every request can become an object-store read
and joblib deserialization path.

ML-service needs a serving cache that keeps hash-verified model bundles loaded
by `modelVersionId`, while preserving artifact hash safety and explicit
unavailable responses.

## What Changes

- Add an in-process model artifact cache for trained-model inference.
- Key cache entries by `modelVersionId`, manifest URI, and artifact hash.
- Verify bundle hash and file hashes before a model enters the cache.
- Reuse warm cached pipelines for subsequent inference requests.
- Evict or reload entries when selected metadata changes or TTL/capacity limits
  require it.
- Expose metrics/logs for cache hit, miss, load failure, and evictions.

## Impact

Affected specs:

- `ml-artifact-runtime`

Affected implementation areas:

- `ml-service/app/services/inference_service.py`
- `ml-service/app/services/artifact_store.py`
- new ML-service serving-cache component
- `ml-service/app/tests/`
- `docs/operations/ml-operations.md`

Compatibility:

- Response contracts remain backward-compatible.
- Missing or invalid artifacts still return explicit `MODEL_UNAVAILABLE`
  responses.
