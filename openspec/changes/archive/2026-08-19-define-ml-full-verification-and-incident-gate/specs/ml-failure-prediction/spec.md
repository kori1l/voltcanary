## ADDED Requirements

### Requirement: Full failure-prediction verification includes negative paths

Failure-prediction verification SHALL cover successful supervised training and
the failure modes that prevent or reject trained-model serving.

#### Scenario: Full failure model gate runs

- **GIVEN** synthetic telemetry includes normal periods, pre-failure drift, and
  corrective maintenance outcomes
- **WHEN** the failure-prediction verification gate runs
- **THEN** it SHALL create a ready dataset, generate horizon labels, generate a
  matching feature snapshot, train a model, approve it, deploy it, and verify
  live inference
- **AND** it SHALL verify label insufficiency, horizon mismatch, feature
  mismatch, missing artifact, fallback, and maintenance exclusion paths.

#### Scenario: Failure prediction creates incident evidence

- **GIVEN** a deployed failure model produces high failure risk
- **WHEN** alert-service processes analytics output
- **THEN** verification SHALL prove an incident-equivalent alert is created or
  explicitly document the missing product behavior as a failing gate
- **AND** the evidence SHALL include alert id, severity, status, source event,
  model version id, input hash, and policy source when available.

### Requirement: Failure verification evidence is durable and sanitized

Failure-prediction verification SHALL write enough evidence for release review
without leaking credentials or committing generated model artifacts.

#### Scenario: Verification completes

- **GIVEN** the full failure-prediction gate completes or fails
- **WHEN** evidence is written
- **THEN** the summary SHALL include scenario statuses, created resource ids,
  model artifact hashes, feature hashes, prediction trace fields, alert ids,
  and screenshot paths
- **AND** SHALL exclude tokens, secrets, source credentials, and full generated
  artifacts.
