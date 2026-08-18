## ADDED Requirements

### Requirement: Production failure-prediction smoke is CI-gated

The complete failure-prediction lifecycle smoke SHALL be runnable as a CI-safe
gate for ML-affecting changes.

#### Scenario: ML runtime change is proposed

- **GIVEN** a change affects ML-service, analytics ML lifecycle, feature
  contracts, model registry, prediction traceability, or ML deployment wiring
- **WHEN** CI or an equivalent required validation job runs
- **THEN** the production smoke SHALL execute or be explicitly marked as not
  required with a documented reason
- **AND** the result SHALL identify the lifecycle step that passed or failed.

#### Scenario: Smoke verifies model routing

- **GIVEN** the smoke trains and deploys a candidate model
- **WHEN** live prediction recalculation runs
- **THEN** the smoke SHALL prove that the selected `modelVersionId` was used
  for inference or explicitly rejected with a controlled status
- **AND** persisted traceability SHALL include model id, feature hash, input
  hash, policy source, fallback reason when applicable, and failure-risk fields.

#### Scenario: Smoke publishes diagnostics

- **GIVEN** the smoke completes or fails
- **WHEN** CI stores the result
- **THEN** the artifact SHALL include a compact lifecycle summary and relevant
  diagnostics
- **AND** SHALL NOT expose secrets or full generated model artifacts.
