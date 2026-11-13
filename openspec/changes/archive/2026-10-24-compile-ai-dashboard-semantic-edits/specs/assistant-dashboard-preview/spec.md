## ADDED Requirements

### Requirement: Typed semantic dashboard operation protocol
New-protocol models SHALL describe bounded domain operations over the selected review and authorized data choices; application code SHALL compile them through ordinary dashboard preparation without accepting raw queries, full ordering or definition JSON from the model.

#### Scenario: Compound current-values and history edit
- **WHEN** the user asks to move speed and next stop to the top, add current door state there and show door history as a graph
- **THEN** compilation reuses existing compatible widgets, creates only missing roles and submits a bounded ordinary preparation intent

#### Scenario: Model supplies arbitrary technical fields
- **WHEN** an operation includes raw coordinates, bindings, URLs or query code
- **THEN** validation rejects the candidate and leaves the accepted review unchanged

### Requirement: Stable targeted edit compilation
The compiler SHALL preserve untargeted widget identity, data and settings, derive permitted changes itself and preserve untouched relative order while allowing only necessary declared responsive reflow.

#### Scenario: Move cards above map
- **WHEN** three current-value cards move to the first row
- **THEN** the map and histories retain their identities, bindings, settings and relative order even if their rows shift downward to avoid collisions

#### Scenario: Hide headers only
- **WHEN** the user requests only header visibility changes
- **THEN** widget geometry, source queries, values and unrelated presentation settings remain unchanged

#### Scenario: Repeated role already exists
- **WHEN** a show-current operation uniquely matches an existing current-value widget
- **THEN** the compiler reuses its key instead of creating a duplicate or converting a history widget

### Requirement: Compatible operation rollout and exact restoration
Stored legacy reviews SHALL retain their original execution payload, and restoration SHALL reuse an authorized immutable definition independently of model-generated layouts.

#### Scenario: Restore then edit
- **WHEN** the user restores a prior version and submits a later refinement
- **THEN** restoration returns the exact stored definition and the later operation compiler uses that definition as its base

#### Scenario: Legacy prepared dashboard executes
- **WHEN** a valid unexpired legacy template or composition review is confirmed
- **THEN** existing owning-service authorization, digest and idempotency checks execute its original reviewed definition without a new-model reinterpretation
