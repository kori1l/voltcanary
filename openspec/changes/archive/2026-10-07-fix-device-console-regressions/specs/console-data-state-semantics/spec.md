## ADDED Requirements

### Requirement: Current device load feedback

Device details SHALL derive load failures from the current resource state, ignore superseded requests, provide an explicit retry for actual failures and clear recovered errors. Failure to load locations SHALL not imply that the device is unavailable.

#### Scenario: A superseded request finishes after successful load

- **WHEN** a repeated or superseded request finishes after the current device was loaded successfully
- **THEN** the device remains available without a generic device-context error.

#### Scenario: A failed device request recovers

- **WHEN** retry successfully loads the device
- **THEN** the error clears and the device view is restored.

### Requirement: Concise contextual freshness feedback

Successful idle sections SHALL avoid repetitive unlabeled refresh timestamp rows. Stale retained evidence and active refresh SHALL remain identifiable by section name, retrieval time and retry/progress state.

#### Scenario: Several sections load successfully

- **WHEN** independent sections become ready
- **THEN** healthy feedback does not create a stack of identical Last refreshed rows.

#### Scenario: Retained evidence becomes stale

- **WHEN** a section refresh fails
- **THEN** its name, failure, previous retrieval time and retry action remain visible.
