## ADDED Requirements

### Requirement: Configured boolean graphs
Configured stored booleans SHALL be selectable in telemetry graphs and retain their boolean metadata. Series SHALL encode false as zero and true as one while preserving source timestamps and gaps; raw JSON SHALL retain boolean values. Real stored configured boolean history SHALL become available on the deployed PostgreSQL storage.

#### Scenario: Door state selection
- **GIVEN** configured doorsOpen observations include false and true
- **WHEN** the user selects the field in telemetry graphs
- **THEN** the graph shows recorded closed/open states or explicit aggregated state ranges, without interpolating fractional states or filling missing observations

#### Scenario: Historical and analytics compatibility
- **GIVEN** real stored configured boolean JSON and ordinary numeric telemetry
- **WHEN** boolean graph support is deployed
- **THEN** existing boolean history is projected using its original timestamps, raw JSON remains unchanged and automatic numeric analytics does not receive configured boolean states
