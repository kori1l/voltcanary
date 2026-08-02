## Context

A feature snapshot must be more than metadata. It is the training input that
ties a dataset version, feature code/config, labels, and split assignment to the
model artifact. If a snapshot cannot be loaded by the training worker and cannot
be regenerated online for inference, the model lifecycle is not reproducible.

## Goals

- Produce full feature matrices for internal and external dataset versions.
- Make feature schemas deterministic and versioned.
- Use the same feature contract for offline training and live inference.
- Support generic and device-type-specific feature templates.
- Fail safely when features are missing or incompatible.

## Non-Goals

- Do not implement supervised failure labels in this change.
- Do not implement model training in this change.
- Do not remove existing baseline fallback.

## Design

### Feature Row Shape

Each materialized feature row should include:

- `accountId`
- `entityId`
- `deviceId` or `virtualDeviceKey`
- `deviceType`
- `predictionAt`
- `horizonDays` when applicable
- feature columns
- optional label columns
- split assignment when configured
- source row lineage
- quality flags

### Feature Hashes

Feature snapshots should store:

- `featureSchemaHash`: hash of ordered feature column names/types and feature
  config;
- `featureDataHash`: hash of generated row identities and feature values;
- `featureConfigHash`: hash of feature config parameters.

The current schema hash alone is insufficient because it can stay the same while
feature values change.

### Offline/Online Parity

Online inference must generate the same feature columns used in training. The
implementation may share Python code, expose a feature-generation endpoint, or
duplicate logic only if parity tests prove equivalence.

### Feature Families

Initial generic feature families:

- rolling statistics;
- robust z-score;
- baseline deviation;
- trend/slope;
- EWMA/CUSUM drift;
- runtime and idle estimates;
- cycle features;
- missing-data features;
- voltage/current instability;
- time-of-day and day-of-week;
- maintenance proximity/exclusion flags;
- device metadata features.

Device-type templates should initially cover generic appliance plus one or two
high-value types such as fridge/freezer and HVAC/air-conditioner.

## Risks

- Feature generation can become expensive. Use batch processing and artifacts
  for training, and bounded windows for online inference.
- Online/offline code sharing across Java and Python can be difficult. Contract
  tests are mandatory even if code is not shared.
- Device-type templates must be versioned so existing models remain loadable.
