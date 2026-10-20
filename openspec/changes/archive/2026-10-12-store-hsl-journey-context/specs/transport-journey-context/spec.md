## ADDED Requirements

### Requirement: Retain observed transport journey context
The system SHALL accept a bounded whitelisted optional transport context with valid GPS telemetry and preserve its source timestamp, route, destination, next stop identifier, schedule offset, doors and other available journey fields in accepted GPS history. Numeric analytics contracts SHALL remain compatible.

#### Scenario: Missing or changed journey fields
- **WHEN** a new GPS observation omits a field or changes the route
- **THEN** its saved context represents that observation without inventing or carrying forward missing values
- **AND** older GPS rows without context remain readable

#### Scenario: Invalid context received directly from a broker
- **WHEN** context contains unknown fields, invalid types or unbounded values
- **THEN** it is rejected before accepted GPS projection

#### Scenario: Real HSL observation
- **WHEN** the connector receives a valid VP message with route, stop, schedule and door information
- **THEN** those sampled values are available through authorized latest and history reads
