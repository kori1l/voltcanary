# Add ML Serving Artifact Cache Design

## Cache Key

The cache key should include:

- `modelVersionId`;
- `manifestUri`;
- `artifactHash`;
- runtime loader or artifact contract version when available.

This prevents stale reuse when a registry entry points the same model id at a
new manifest or hash during recovery.

## Cached Value

The cached value should contain:

- parsed manifest;
- parsed `model.json`;
- loaded `model.joblib` pipeline;
- model type/family;
- feature columns and feature schema hash;
- loaded-at timestamp and source metadata.

The cache must never skip the first load verification. Hash verification occurs
before a value becomes visible to inference.

## Eviction

Initial implementation can use a bounded LRU or TTL cache. Settings should
control:

- maximum model entries;
- entry TTL;
- whether to preload active models later.

Eviction must be safe: the next request can reload and verify the model. A load
failure after eviction returns `MODEL_UNAVAILABLE` rather than falling through
to an incompatible trained response.

## Observability

Metrics/log events should identify:

- cache hit/miss by model version id;
- load duration;
- artifact verification failures;
- evictions;
- current cache size.

Do not tag metrics with high-cardinality manifest URIs.

## Tests

Use fake artifact stores to prove:

- first trained inference loads and verifies the bundle;
- second trained inference reuses the cached pipeline;
- metadata hash change forces reload;
- corrupted or missing artifacts are not cached;
- cache works for anomaly and failure model families.
