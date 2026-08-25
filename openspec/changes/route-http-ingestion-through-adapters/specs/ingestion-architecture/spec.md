# Ingestion Architecture

## ADDED Requirements

### Requirement: HTTP Ingestion Routes Through The Adapter Boundary

HTTP single, batch, and import requests SHALL be translated to canonical
telemetry input through the HTTP adapter before common ingestion processing.

#### Scenario: Single HTTP reading is accepted

- **WHEN** `POST /ingestion/readings` receives a valid realtime reading
- **THEN** the controller SHALL delegate to ingress orchestration
- **AND** the HTTP adapter SHALL produce canonical telemetry input for the
  common pipeline.

#### Scenario: Batch HTTP reading is accepted

- **WHEN** `POST /ingestion/readings/batch` receives multiple readings
- **THEN** each reading SHALL pass through the same canonical validation,
  idempotency, rate-limit, and event creation invariants as single ingestion
- **AND** batch-level optimizations SHALL NOT bypass per-message correctness.

#### Scenario: Historical import is accepted

- **WHEN** an import endpoint accepts historical telemetry
- **THEN** the HTTP adapter SHALL set canonical import mode
- **AND** the common pipeline SHALL apply the import timestamp policy without a
  separate telemetry semantics stack.
