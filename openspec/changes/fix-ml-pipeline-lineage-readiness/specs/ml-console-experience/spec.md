## ADDED Requirements

### Requirement: Selected lineage readiness

ML readiness SHALL be evaluated against one selected dataset version, scope, family, horizon, snapshot, run, model and policy; unknown lineage SHALL remain unverifiable rather than ready.

#### Scenario: Unrelated assets exist

- **GIVEN** an anomaly dataset A is selected and other assets reference B
- **WHEN** pipeline readiness is evaluated
- **THEN** B assets SHALL NOT satisfy A prerequisites; remediation retains selected A context.

#### Scenario: Failure horizon mismatch

- **GIVEN** a failure-prediction context requires seven days
- **WHEN** a fourteen-day snapshot or model is selected
- **THEN** the relevant step explains the mismatch and cannot claim ready.

### Requirement: Training progress and serving evidence are distinct

QUEUED and RUNNING jobs SHALL represent progress rather than completed training, and model approval or configured deployment SHALL NOT imply observed runtime inference.

#### Scenario: Running training run

- **GIVEN** a matching run is RUNNING
- **WHEN** the pipeline is rendered
- **THEN** training is in progress and a completed evaluated model is not implied.

#### Scenario: Approved model without policy

- **GIVEN** a model is approved but its compatible policy is disabled
- **WHEN** deployment readiness is evaluated
- **THEN** model approval remains visible, policy readiness is blocked, and runtime status stays independent.
