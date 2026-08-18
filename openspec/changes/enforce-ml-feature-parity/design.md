# Enforce ML Feature Parity Design

## Contract Metadata

Feature compatibility should include:

- `featureVectorContractVersion`;
- `featureSetVersion`;
- `featureTemplateId`;
- `featureTemplateVersion`;
- `modelFamily`;
- `horizonDays` when required;
- `generatorVersion`;
- ordered required columns;
- feature schema hash;
- feature config hash when available.

The selected model artifact should record the same metadata. Live inference
should compare both the schema hash and the contract metadata that explains how
the hash was produced.

## Shared Logic

The preferred implementation is to centralize feature contract construction so
offline snapshot generation and online inference use the same helper logic. If
full code sharing is not practical in the first slice, add parity tests with
golden fixtures and keep the duplicate code paths locked to the same expected
outputs.

## Parity Tests

Tests should build a small dataset with:

- device id/type;
- metric name/unit;
- baseline summary;
- recent window summary;
- maintenance exclusion;
- failure horizon where applicable.

The offline feature row and online inference vector for the same prediction
point must match for required columns and produce the same contract metadata.

## Runtime Behavior

When online feature generation cannot satisfy the selected model contract:

- analytics-service should persist a fallback reason such as
  `INCOMPATIBLE_FEATURE_SCHEMA`, `FEATURES_UNAVAILABLE`, or
  `FEATURE_CONTRACT_VERSION_MISMATCH`;
- ML-service should return `FEATURE_SCHEMA_MISMATCH` or
  `FEATURES_UNAVAILABLE` when it receives incompatible metadata;
- traceability should include generated and required schema hashes.

## Diagnostics

Diagnostics should identify:

- missing required columns;
- type/value incompatibility for required numeric features;
- generator/template/horizon mismatch;
- maintenance or data-quality flags that lower confidence.
