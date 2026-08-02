## 1. Feature Contracts

- [x] 1.1 Define canonical feature row fields.
- [x] 1.2 Define feature vector contracts for anomaly, degradation, and failure prediction families.
- [x] 1.3 Add feature schema hash, data hash, and config hash metadata.
- [x] 1.4 Document feature contract versioning.

## 2. Offline Feature Materialization

- [x] 2.1 Generate full feature matrices for external dataset versions.
- [x] 2.2 Generate full feature matrices for internal telemetry dataset versions.
- [x] 2.3 Persist feature artifacts or immutable feature row references.
- [x] 2.4 Store row counts, rejected counts, quality flags, and artifact hashes.
- [x] 2.5 Add tests for deterministic feature data hashes.

## 3. Online Feature Generation

- [x] 3.1 Add live feature generation for the active prediction window.
- [x] 3.2 Include device type, metric schema, maintenance state, and data quality flags.
- [x] 3.3 Return explicit errors for missing required features.
- [x] 3.4 Add online/offline parity tests using fixed fixtures.

## 4. Feature Templates

- [x] 4.1 Add generic appliance feature template.
- [x] 4.2 Add fridge/freezer or HVAC feature template.
- [x] 4.3 Version template configs and generated feature names.
- [x] 4.4 Add tests for template compatibility by device type.

## 5. Frontend And Docs

- [x] 5.1 Show feature snapshot artifact/hash metadata.
- [x] 5.2 Let operators select feature templates and key parameters.
- [x] 5.3 Show feature-generation failures with actionable reasons.
- [x] 5.4 Update ML API and operations docs.

## 6. Verification

- [x] 6.1 Run analytics-service feature pipeline tests.
- [x] 6.2 Run ML-service feature tests if Python owns transformations.
- [x] 6.3 Run frontend ML workflow tests.
- [x] 6.4 Run `npm run openspec:validate`.
