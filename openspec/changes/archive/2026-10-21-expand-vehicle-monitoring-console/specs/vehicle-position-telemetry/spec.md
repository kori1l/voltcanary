## MODIFIED Requirements

### Requirement: Geographic metrics are validated as one position
Ingestion and telemetry SHALL validate finite paired latitude[-90,90]/longitude[-180,180], optional speedKmh[0,300], headingDeg[0,360), ignition integer0/1, fuelPercent[0,100] and odometerKm[0,10000000] in one source/timestamp message. Invalid values SHALL NOT produce accepted positions. Missing optional values SHALL remain null; zero SHALL remain zero. Existing non-GPS messages and v1 envelope/topics SHALL remain compatible.
#### Scenario: Accepted stopped vehicle
- **WHEN** a paired GPS observation contains zero speed, ignition and fuel
- **THEN** zeros are stored atomically and remain distinguishable from missing fields
#### Scenario: Invalid optional field
- **WHEN** optional vehicle readings are outside bounds
- **THEN** the envelope is rejected without creating an accepted position
#### Scenario: Negative coordinates and zero speed
- **WHEN** a complete GPS message contains valid negative coordinates and speedKmh=0
- **THEN** those values are accepted without nonnegative clamping or replacing zero with unknown.
#### Scenario: Unpaired or invalid geographic input
- **WHEN** only one coordinate, a nonfinite value, latitude 91 or headingDeg 360 is supplied
- **THEN** ingress rejects the message and telemetry independently prevents a GPS projection for equivalent broker input.

## ADDED Requirements

### Requirement: Bounded chronological recordings
The system SHALL expose authorized ascending cursor recordings with an immutable time cutoff, maximum31day range and maximum2000samples per page, while preserving original latest/history dashboard bounds.
#### Scenario: Next cursor boundary
- **WHEN** the client requests the next page using nextCursor
- **THEN** the last sample of the preceding page is excluded and subsequent recorded samples are returned without skipping the boundary
