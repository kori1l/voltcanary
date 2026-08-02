## Context

The existing metadata schema is a useful start, but a data engineer cannot rely
on a dataset version unless the exact training rows are frozen. Query lineage is
not enough because telemetry retention, correction, ingestion replay, unit
normalization, and API behavior can change after the dataset is created.

External import has the opposite problem: rows are stored, but the rest of the
workflow starts from `ml_dataset_versions`. The import path therefore stops
before feature generation.

## Goals

- Make every `READY` ML dataset version reproducible.
- Make internal telemetry and external imports produce the same canonical row
  shape.
- Make imported files usable without manual database intervention.
- Keep raw imported data separate from live telemetry tables.
- Record enough quality metadata for a data engineer to decide whether training
  is valid.

## Non-Goals

- Do not implement feature generation in this change.
- Do not train real models in this change.
- Do not introduce failure-outcome labels in this change.
- Do not remove existing baseline/rule fallback behavior.

## Design

### Canonical ML Row

Dataset materialization should produce rows with at least:

- `accountId`
- `deviceId` or `externalDeviceKey`
- `virtualDeviceKey`
- `deviceType`
- `sourceId` or `sensorId` when available
- `timestamp`
- `metricName`
- `canonicalMetricName`
- `numericValue`
- `rawUnit`
- `canonicalUnit`
- `qualityFlags`
- `metadataJson`
- `sourceLineage`

The implementation may store these rows in a relational table first, but each
READY dataset version must expose a stable artifact URI/hash or an equivalent
immutable table reference with a data hash.

### Internal Telemetry

Internal dataset creation should:

1. authorize selected devices;
2. export selected telemetry rows;
3. normalize metrics and units;
4. join device type and relevant metadata snapshots;
5. write immutable canonical rows;
6. compute data hash and quality profile;
7. mark the dataset version READY only after materialization succeeds.

### External Import

External CSV/JSONL import should create a dataset and a dataset version. Partial
acceptance may still be allowed, but the dataset version must remain
`VALIDATING` until accepted rows are materialized into canonical rows for that
version. If all rows are rejected, the version must be `FAILED`.

Mapped `existingDeviceId` values must be verified against the importing account.
Unverified mappings must be rejected or quarantined.

### Data Quality Profile

The validation report should grow beyond accepted/rejected counts. It should
track:

- row count;
- device count;
- metric count;
- time range;
- coverage by device/metric;
- max sampling gap;
- duplicate timestamp rate;
- non-finite/impossible value count;
- unit normalization warnings;
- maintenance overlap count;
- label coverage when labels exist.

### Frontend

The ML console should expose internal telemetry dataset creation. A user should
be able to select devices, metrics, time range, split strategy, and minimum
quality gates, then see the resulting dataset version and quality report.

## Risks

- Large exports can stress telemetry-service if implemented with chart-oriented
  APIs. The implementation should use streaming/pageable extraction or a
  dedicated export endpoint.
- Artifact storage can be introduced incrementally, but the dataset version
  must still have an immutable reference and hash.
- Existing demo workflows may assume imports appear immediately in the dataset
  list; this change should make that assumption true instead of preserving the
  broken behavior.
