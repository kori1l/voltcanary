## ADDED Requirements

### Requirement: ML console tracks long-running horizon label generation

The ML Settings Labels workflow SHALL present horizon label generation as a
status-tracked operation instead of surfacing gateway timeouts as user-facing
errors.

#### Scenario: Operator starts label generation

- **GIVEN** the operator selected a dataset version and horizon configuration
- **WHEN** the operator starts horizon label generation
- **THEN** the UI SHALL show the returned job status
- **AND** disable duplicate start actions while the job is `QUEUED` or
  `RUNNING`.

#### Scenario: Generation is still running

- **GIVEN** a horizon label generation job is `QUEUED` or `RUNNING`
- **WHEN** the Labels tab is visible
- **THEN** the UI SHALL poll or provide a clear check-status action
- **AND** keep the last known status visible if a status refresh temporarily
  fails.

#### Scenario: Generation completes

- **GIVEN** a horizon label generation job reaches `COMPLETED`
- **WHEN** the UI receives the completed status
- **THEN** it SHALL show generated, positive, normal, unobservable counts and
  lineage hash
- **AND** refresh the dataset label list for the selected dataset version.

#### Scenario: Generation fails

- **GIVEN** a horizon label generation job reaches `FAILED`
- **WHEN** the UI receives the failed status
- **THEN** it SHALL show the sanitized failure reason
- **AND** allow the operator to retry generation after adjusting inputs or
  dependencies.

#### Scenario: Gateway request times out

- **GIVEN** the browser or gateway times out while starting or polling a horizon
  label generation job
- **WHEN** the UI handles the timeout
- **THEN** it SHALL not show raw gateway endpoint errors as the primary workflow
  state
- **AND** it SHALL preserve any known job status and offer status refresh.
