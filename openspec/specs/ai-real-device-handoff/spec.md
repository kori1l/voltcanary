# ai-real-device-handoff Specification

## Purpose
Define truthful single-device AI handoff, unsupported-request clarification and
visibility of real authorized GPS sources in the ordinary device workflow.
## Requirements
### Requirement: Single device integration handoff
The assistant SHALL distinguish counts of telemetry observations from requested
device quantities and create only one reviewed device. When the independent
device-setup feature is enabled, a supported typed integration MAY be connected
as part of the reviewed device workflow. Unsupported integrations SHALL state
the separate source setup step without claiming connector deployment.
#### Scenario: Several measurements for one bus
- **WHEN** a request names one vehicle and asks to verify several fresh measurements
- **THEN** the quantity guard does not reject it as multiple devices
#### Scenario: Two devices
- **WHEN** the request explicitly asks to create two devices
- **THEN** clarification is required and no creation occurs
#### Scenario: Supported HSL source
- **WHEN** an owner confirms one device with an enabled HSL integration
- **THEN** the fixed adapter provisions the source and reports waiting or actual fresh evidence separately from device creation

### Requirement: Truthful unsupported planning
Rules SHALL NOT substitute empty simulator YAML validation for an unsupported
request, and the assistant SHALL label the Vehicles page context accurately.
#### Scenario: Missing YAML and unknown task
- **WHEN** no supported rule matches and no YAML was supplied
- **THEN** the plan has no executable actions and explains the needed clarification

### Requirement: Real GPS source visibility
The device Movement view SHALL support authorized REST GPS sources including
real API and sensor sources, retain simulator compatibility and require explicit
selection when several sources exist. Real source setup SHALL link to Configuration.
#### Scenario: Connected real bus
- **WHEN** a vehicle has one API REST source with accepted GPS observations
- **THEN** Movement selects that source and displays its evidence without suggesting simulation

