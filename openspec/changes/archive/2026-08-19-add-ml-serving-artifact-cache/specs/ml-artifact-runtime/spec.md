## ADDED Requirements

### Requirement: Trained-model inference uses a verified serving cache

ML-service SHALL reuse previously verified trained-model runtime artifacts for
serving when the selected model metadata has not changed.

#### Scenario: First request loads and verifies a model

- **GIVEN** analytics-service selects a trained model with `modelVersionId`,
  `manifestUri`, and `artifactHash`
- **WHEN** ML-service serves the first compatible inference request for that
  model
- **THEN** it SHALL load the artifact bundle from the artifact store
- **AND** verify the manifest hash and file hashes before scoring
- **AND** place the loaded runtime artifact in the serving cache.

#### Scenario: Subsequent request reuses cached runtime

- **GIVEN** a verified cache entry exists for the selected model version,
  manifest URI, and artifact hash
- **WHEN** another compatible inference request selects the same metadata
- **THEN** ML-service SHALL score using the cached runtime artifact
- **AND** SHALL NOT reread or deserialize the same bundle from artifact storage
  for that request.

#### Scenario: Model metadata changes

- **GIVEN** a cache entry exists for a model version
- **WHEN** analytics-service selects the same model version with a different
  manifest URI or artifact hash
- **THEN** ML-service SHALL treat the cache entry as stale for that request
- **AND** reload and verify the new artifact metadata before scoring.

#### Scenario: Artifact load fails

- **GIVEN** a selected model bundle cannot be loaded or verified
- **WHEN** ML-service attempts to populate the serving cache
- **THEN** it SHALL NOT cache the failed artifact
- **AND** it SHALL return an explicit model-unavailable response.
