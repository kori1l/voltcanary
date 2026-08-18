## ADDED Requirements

### Requirement: Horizon label generation is asynchronous and status tracked

Analytics-service SHALL run horizon label generation as a durable status-tracked
job rather than relying on a single gateway-routed synchronous HTTP request.

#### Scenario: Operator starts horizon label generation

- **GIVEN** an authorized operator selects a READY dataset version and horizon
  label configuration
- **WHEN** the operator starts horizon label generation
- **THEN** analytics-service SHALL return a job id and status without requiring
  label generation to finish in the initial HTTP request
- **AND** the returned status SHALL be one of `QUEUED`, `RUNNING`, `COMPLETED`,
  or `FAILED`.

#### Scenario: Operator polls generation status

- **GIVEN** a horizon label generation job exists for the operator account
- **WHEN** the operator requests job status by id
- **THEN** analytics-service SHALL return the current status, request
  configuration, timestamps, progress or status message, and sanitized failure
  reason when failed
- **AND** when the job is `COMPLETED`, the response SHALL include generated,
  positive, normal, maintenance-excluded, unresolved, unobservable counts, and
  lineage hash.

#### Scenario: Generation request is repeated

- **GIVEN** a horizon label generation job already exists for the same account,
  dataset version, horizon days, generation version, preventive-maintenance
  flag, and input lineage
- **WHEN** the operator submits the same generation request again
- **THEN** analytics-service SHALL return the existing active or completed job
  status instead of starting duplicate generation.

#### Scenario: Generation fails after acceptance

- **GIVEN** a horizon label generation job was accepted
- **WHEN** generation fails while planning or persisting labels
- **THEN** analytics-service SHALL mark the job `FAILED`
- **AND** preserve a sanitized failure reason for operators
- **AND** allow a subsequent retry request when the failure condition is fixed.

### Requirement: Horizon label jobs preserve deterministic lineage

Asynchronous execution SHALL preserve the deterministic and auditable label
lineage guarantees of horizon label generation.

#### Scenario: Completed async job records lineage

- **GIVEN** an async horizon label generation job completes
- **WHEN** generated labels are stored
- **THEN** each generated label SHALL retain generation version, horizon days,
  source outcome references when applicable, and lineage hash
- **AND** rerunning with the same inputs SHALL produce the same lineage hash.
