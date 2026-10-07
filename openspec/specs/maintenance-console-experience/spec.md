# maintenance-console-experience Specification

## Purpose
Define readable maintenance planning, shared validated editors, accurate independent exclusion policies and operator outcome evidence.
## Requirements
### Requirement: Readable timeline agenda and filters

Maintenance SHALL provide readable Timeline and Agenda views, untruncated date filters, bounded local timeline scrolling, persistent device context and a useful mobile Agenda.

#### Scenario: A short operation or long device name is shown

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the viewport narrows or the timeline scrolls
- **THEN** the operation can be identified, full details are accessible and page-level horizontal scrolling is avoided.

### Requirement: Shared reviewed maintenance editor

Fleet and device scheduling/editing SHALL use shared controls in opaque accessible panels, named device selection, explicit timezone, end-after-start validation, past-interval warning and a reachable footer.

#### Scenario: An operator chooses an invalid or past interval

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** scheduling is attempted
- **THEN** invalid order is blocked locally, a past interval is clearly warned and the device selection/draft remain intact.

### Requirement: Accurate downstream impact

Maintenance impact summaries SHALL reflect suppressAlerts, excludeFromAnalytics and excludeFromMl independently and preserve their existing server semantics for creation, editing and cancellation.

#### Scenario: Only some maintenance exclusions are selected

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator reviews or changes the operation
- **THEN** the summary describes only enabled effects and submitted payloads retain distinct flags and affected devices.

### Requirement: Outcome-oriented result recording

Record outcome SHALL group result, time, failure details and notes; show relevant failure fields by outcome; explain downstream ML label use; and display a saved result in the operation history.

#### Scenario: An operator records inspection-normal or confirmed-failure outcome

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the record is reviewed and saved
- **THEN** required fields match the chosen result, canonical outcome values are submitted and the history reflects the persisted result without claiming an unverified diagnosis.

