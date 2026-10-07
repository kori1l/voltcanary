# ml-console-experience Specification

## Purpose
Define guided ML console preparation, compatible training inputs, auditable labels, reviewed lifecycle/deployment decisions and evidence-backed monitoring.
## Requirements
### Requirement: Actionable ML pipeline and scope

ML SHALL provide a collapsible mode-specific pipeline with readable prerequisite state and remediation links, named account/device selectors and explicit inheritance. Confidence warnings SHALL be explained as thresholds rather than prediction accuracy.

#### Scenario: Failure prediction is selected with missing prerequisites

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator follows a blocked pipeline step
- **THEN** the required labels/horizon/snapshot task opens with consistent mode/scope and the page avoids redundant anomaly-only prerequisites.

### Requirement: Preview-bound dataset preparation

Workbench SHALL order scope, metrics, period, readiness and commit, explain blocking reasons and use only backend-preview metric availability. Any relevant draft change SHALL invalidate preview and keep creation disabled until a matching fresh result exists.

#### Scenario: A stale or out-of-order preview arrives

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the scope, range, family, horizon, split, feature configuration or quality gates have changed
- **THEN** the obsolete response cannot enable Create or introduce phantom metrics and the operator can obtain a fresh preview without losing the draft.

### Requirement: Readable dataset snapshot and import workflows

Dataset/snapshot detail SHALL prioritize quality, readiness and compatibility before IDs/hashes/lineage. Import SHALL provide bounded CSV/JSONL file preview, visual column mapping, validation and accepted/rejected results with raw mapping JSON advanced.

#### Scenario: An imported file or snapshot has a mismatch

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator reviews mapping or training readiness
- **THEN** field/row errors and family/horizon incompatibility are shown before submission and canonical schema/lineage contracts are preserved.

### Requirement: Auditable labels and generation

Labels SHALL use named devices/metrics, readable outcome labels, interval evidence and reasoned correction with accessible audit history. Horizon generation SHALL display dataset scope, horizon, progress and final counts while preventing duplicate jobs.

#### Scenario: Generation refresh fails while a job is running

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator returns to Labels
- **THEN** the known job/draft is retained with retry, no duplicate start is offered and completion refreshes labels and lineage information.

### Requirement: Guided contained training

Training SHALL stay within responsive columns and guide dataset, compatible snapshot, algorithm and review with actionable prerequisites, explicit horizons/gates and visible job progress and retry/cancel consequences.

#### Scenario: No compatible READY dataset or snapshot exists

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator prepares a training run
- **THEN** the missing prerequisite has a useful action, empty selects do not imply readiness and queueing is blocked before API submission.

### Requirement: Reviewed model and deployment decisions

Model registry SHALL prioritize family, horizon, state and evaluation gates, with artifact and lineage detail secondary. Policy editing SHALL select scope, purpose and compatible model, explain Shadow/Canary/Active/fallback and show effective policy before applying valid configuration.

#### Scenario: An active policy or model promotion is requested

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the model lacks required compatibility or approval evidence
- **THEN** the UI explains the block and does not waive server gates; archive/delete uses dependency impact preview and a reviewed reason.

### Requirement: Evidence-backed ML monitoring

Monitoring SHALL display freshness, observation window and denominators beside rates, render unavailable prediction evidence as insufficient-data rather than 0%, and explain serving path, fallback and confidence.

#### Scenario: There are no recent prediction observations

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** monitoring is opened
- **THEN** error/fallback/quality are unknown with a next step rather than fabricated zero rates and no trained-model success is implied.

