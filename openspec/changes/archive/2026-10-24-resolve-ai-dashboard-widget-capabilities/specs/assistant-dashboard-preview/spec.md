## ADDED Requirements

### Requirement: Authorized dashboard data capability resolution
The dashboard application SHALL resolve compatible widgets and views from current authorized device/source metadata and the shared widget descriptor, preserving data-family identity and semantic prerequisites.

#### Scenario: Door field is not a numeric metric
- **WHEN** the selected source records a boolean door field and the user requests its current state and recorded history
- **THEN** the resolver offers the supported source-bound current and history widgets without inventing a registered metric

#### Scenario: Two sources expose the same label
- **WHEN** two authorized sources match the requested field and neither is explicitly selected
- **THEN** the system reports a meaningful source choice instead of selecting the first result

#### Scenario: Numeric energy input lacks required semantics
- **WHEN** a numeric source has no required counter or tariff semantics for the requested energy/cost widget
- **THEN** numeric type compatibility alone does not mark that widget ready

### Requirement: Capability is distinct from observed availability
Capability resolution SHALL distinguish missing/stale samples, incompatible types and unavailable dependencies, and SHALL NOT fabricate values, units or human-readable labels.

#### Scenario: False and zero are observed
- **WHEN** valid source evidence contains false door state or zero speed
- **THEN** they remain actual observations rather than missing values

#### Scenario: Field exists but interval is empty
- **WHEN** a supported field has no observations in the requested interval
- **THEN** the capability remains valid and the normal preview reports no data without an invented reading

#### Scenario: Text field requested as numeric line
- **WHEN** the only matching field is text and a line history is requested
- **THEN** the resolver exposes the incompatibility and compatible list option without converting text into numbers

### Requirement: Source history view compatibility
AI and ordinary editing SHALL share implemented source-history view constraints, preserve recorded gaps and retain the distinction between observed state and inferred events.

#### Scenario: Door history has an unknown interval
- **WHEN** a boolean line history includes missing observations or a long gap
- **THEN** the graph breaks across the unknown interval and does not invent opening/closing transitions

#### Scenario: Access changes after a choice was stored
- **WHEN** a stored data reference is renewed after access revocation or metadata type change
- **THEN** resolution and final preparation revalidate current authority and reject incompatible or foreign data without altering the last valid review
