# vehicle-position-telemetry Specification

## Purpose
Define accepted atomic GPS observations, optional vehicle readings and authorized
bounded latest, history, cursor recording and dashboard queries.
## Requirements
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

### Requirement: Accepted positions preserve atomic identity and ordering
Telemetry-service SHALL own a transactionally written indexed telemetry_positions projection sourced only from accepted GPS envelopes. It SHALL preserve device/source/time identity, receivedAt and quality, reuse event idempotency and keep the first accepted position for a duplicate source/timestamp. It SHALL NOT assemble coordinates from independent latest scalar metrics or unvalidated raw JSON. History SHALL allow accepted late/backfill points and follow existing telemetry retention.

#### Scenario: Rejected raw reading exists
- **GIVEN** an invalid raw reading was stored for diagnostics
- **WHEN** latest position or history is queried
- **THEN** that raw reading does not appear as an accepted GPS point.

#### Scenario: Duplicate conflict
- **GIVEN** an accepted position for a device/source/timestamp
- **WHEN** an identical or conflicting duplicate arrives
- **THEN** it does not add or overwrite the accepted position.

#### Scenario: Late arrival and different sources
- **WHEN** an older accepted point or a point from another source arrives
- **THEN** the older point enters its source history without replacing a newer latest point and sources are never paired or merged.

### Requirement: Position reads are authorized and bounded
Authenticated latest/history reads SHALL require deviceId and its owned external sourceId. Latest SHALL select recordedAt <= asOf with server-now default and return nullable position, asOf and FRESH/STALE/NO_DATA. Freshness SHALL become STALE above 30 seconds. History SHALL use `(from,to]`, from<to, at most 24h and the newest at most 1000 points in ascending time order, with truthful returnedCount/truncated. Invalid ranges SHALL return standard errors rather than silently widening scope. Earlier-page loading SHALL remain outside v1; clients SHALL offer narrowing the range when truncated.

#### Scenario: Empty and stopped source
- **WHEN** a source has no position or has not emitted for more than 30 seconds
- **THEN** latest returns NO_DATA with null or the last known position with STALE respectively, without inventing (0,0) or extrapolating motion.

#### Scenario: Historical snapshot
- **GIVEN** a position exists after requested asOf
- **WHEN** latest is read at that historical asOf
- **THEN** the later point is excluded and the historical observation time remains visible.

#### Scenario: Bounded history
- **GIVEN** 1200 accepted points in the requested interval
- **WHEN** history is queried
- **THEN** the response contains the newest 1000 points with truncated=true and the UI offers narrowing the range without claiming a complete route.

#### Scenario: Foreign source or revoked access
- **WHEN** a viewer requests another device's source or loses current resource access
- **THEN** public and internal dashboard reads deny disclosure, including through cached evidence, using existing authorization and error conventions.

### Requirement: Dashboard position evidence follows current read boundaries
Dashboard GPS reads SHALL return bounded latest/history evidence through existing authorized internal telemetry adapters and ports with one resolved time context. They SHALL be read-only and SHALL preserve per-panel failure isolation, resource-filter intersections and current cache authorization.

#### Scenario: Historical dashboard interval
- **WHEN** a map uses a fixed historical dashboard interval
- **THEN** latest is no later than the interval end/asOf, history uses that interval and the map does not fetch live-now evidence instead.

#### Scenario: Isolated read failure
- **WHEN** the position dependency fails
- **THEN** the map panel is unavailable while successful unrelated panels remain usable and no run or inference is started.

### Requirement: Bounded chronological recordings
The system SHALL expose authorized ascending cursor recordings with an immutable time cutoff, maximum31day range and maximum2000samples per page, while preserving original latest/history dashboard bounds.
#### Scenario: Next cursor boundary
- **WHEN** the client requests the next page using nextCursor
- **THEN** the last sample of the preceding page is excluded and subsequent recorded samples are returned without skipping the boundary

