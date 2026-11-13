## MODIFIED Requirements
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
