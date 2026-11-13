## ADDED Requirements
### Requirement: Persistent real-device intent
The assistant SHALL preserve explicit name, type and optional device choices
across clarification and SHALL distinguish the original requested quantity from
later alternative names. Ordinary name replies SHALL require no special syntax.
#### Scenario: Repeated name answers
- **WHEN** a user answers a device-name question with a plain name and later replaces that name
- **THEN** one device intent uses the latest name without treating old names as multiple devices

### Requirement: Reviewed supported integration setup
The system SHALL execute supported real-device integration setup separately from
dashboard composition, through authorized domain APIs and a fixed typed adapter.
It SHALL expose actual source readiness, retain created resource identity after
partial failures and reconcile using durable creation identity without duplication.
#### Scenario: Silent real source
- **WHEN** a reviewed HSL source is connected but no fresh accepted GPS observation exists
- **THEN** the result reports waiting for data without claiming live telemetry or creating a simulator
#### Scenario: Uncertain connection response
- **WHEN** source setup has an uncertain outcome after device creation
- **THEN** explicit reconciliation reuses the same device receipt and source identity
#### Scenario: Dashboard compatibility
- **WHEN** a configured device is handed to dashboard creation
- **THEN** existing authorized capability resolution and dashboard review operate on its actual device and source
