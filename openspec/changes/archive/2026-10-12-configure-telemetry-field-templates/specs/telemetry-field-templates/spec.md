## ADDED Requirements

### Requirement: Reusable owner-controlled field storage
The system SHALL let owners define typed telemetry field templates, assign them to sources individually or by device type, and propagate edits to assigned sources. Only enabled available fields SHALL enter accepted storage for configured sources. Unconfigured sources SHALL retain legacy behavior and historical data SHALL remain readable.

#### Scenario: Shared template controls future retention
- **GIVEN** two owned sources assigned the same template
- **WHEN** the owner disables storing a field and new messages arrive after runtime propagation
- **THEN** neither source stores that field in new raw, numeric or position evidence and historical values remain readable

#### Scenario: Authorization and values
- **GIVEN** an owner template with numeric, text and boolean fields
- **WHEN** another owner attempts assignment or invalid paths/types/GPS selection are submitted
- **THEN** the request is rejected, while valid zero and false are retained

### Requirement: Independent map field presentation
Map widgets SHALL offer selection of stored device fields for display independently from source storage policy. Available numeric fields SHALL support charts and stored text/boolean fields SHALL be visible in device telemetry.

#### Scenario: Hide a stored field on one map
- **GIVEN** a source stores speed and destination
- **WHEN** a map widget selects only destination
- **THEN** selected vehicle details show destination and omit speed while source storage and other widgets are unaffected
