# device-variable-series-queries Specification

## Purpose
TBD - created by archiving change query-device-variable-series. Update Purpose after archive.
## Requirements
### Requirement: Unified authorized series selectors

Telemetry SHALL expose measured and computed variable descriptors and common history/latest/chart/export reads using stable variableId plus explicit source identity, preserving legacy selectors.

#### Scenario: Same label different source

- **WHEN** two sources expose variables with the same display name
- **THEN** series selection remains unambiguous by stable identity and source

#### Scenario: Archived history

- **WHEN** an authorized user selects an archived variable with retained measurements
- **THEN** historical data remains readable with its recorded unit and revision

### Requirement: Bounded query-time aggregation

Chart reducers SHALL execute bounded telemetry-owned reads without automatically materializing graph-only series, exposing counts, gaps and explicit revision segments.

#### Scenario: Chart resolution change

- **WHEN** a dashboard changes from minute buckets to hour buckets
- **THEN** telemetry returns a new bounded aggregation without creating stored variable measurements

#### Scenario: Revision boundary

- **WHEN** a selected period crosses a formula revision change
- **THEN** default aggregation separates revision segments and does not silently average them together

### Requirement: Typed dashboard owner reads

Dashboard variable widgets SHALL use authorized typed telemetry ports and descriptor units rather than direct database access or name-based inference.

#### Scenario: Custom temperature name

- **WHEN** a variable key does not encode its Celsius unit
- **THEN** the graph uses descriptor unit and label correctly

#### Scenario: Denied source

- **WHEN** a widget references an inaccessible source
- **THEN** the owner read fails closed without disclosing evidence

### Requirement: Authorization-aware cache and latest

Cached query results SHALL be scoped to renewed authorization and full variable/source/revision query identity; late observations SHALL NOT replace newer latest values.

#### Scenario: Revoked permission

- **WHEN** a cached graph exists when source access is revoked
- **THEN** it is not delivered under the revoked scope

#### Scenario: Late arrival

- **WHEN** an older variable observation arrives after a newer one
- **THEN** history includes it but latest remains the newer observation

