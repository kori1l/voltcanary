# Tasks

## ML Service

- [ ] Add a serving cache component keyed by model version id, manifest URI,
      artifact hash, and artifact contract version.
- [ ] Refactor trained-model inference to request loaded runtime artifacts
      through the cache.
- [ ] Preserve existing `MODEL_UNAVAILABLE`, `MODEL_INCOMPATIBLE`,
      `FEATURES_UNAVAILABLE`, and `FEATURE_SCHEMA_MISMATCH` responses.
- [ ] Add configurable cache max entries and TTL.
- [ ] Add metrics or structured logs for hit, miss, load duration, load
      failure, eviction, and cache size.
- [ ] Add tests for cache hit/miss, hash-change reload, corrupted artifact
      rejection, eviction, anomaly model scoring, and failure model scoring.

## Documentation And Validation

- [ ] Update `docs/operations/ml-operations.md` with cache settings and
      diagnostic steps.
- [ ] Run `cd ml-service; .\.venv\Scripts\python.exe -m unittest discover -s app/tests`.
- [ ] Run `npm run openspec:validate`.
