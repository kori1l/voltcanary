# device-variable-definitions Specification

## Purpose
TBD - created by archiving change define-device-type-variables. Update Purpose after archive.
## Requirements
### Requirement: Account-scoped stable variable identity

The system SHALL store numeric variable definitions in device-service with account/type ownership, stable UUID identity and immutable key/type/output unit; built-in and internal keys SHALL be protected.

#### Scenario: Tenant type isolation

- **WHEN** another account uses the same device type code
- **THEN** it cannot read, edit or bind the first account's variable

#### Scenario: Stable identity

- **WHEN** a label is edited or an incompatible output unit is requested
- **THEN** the label preserves identity and the incompatible unit change requires a new definition

### Requirement: Immutable revisions and explicit binding

Published revisions SHALL be immutable and source bindings SHALL pin an explicit revision using optimistic concurrency.

#### Scenario: Concurrent publication

- **WHEN** two editors publish against the same expected revision
- **THEN** one succeeds and the stale write returns conflict without overwriting published content

#### Scenario: Default update

- **WHEN** the type default changes to revision two
- **THEN** existing source bindings remain pinned until explicit rollout

### Requirement: Deterministic type inheritance

New matching devices and sources SHALL inherit account-scoped defaults only with unambiguous source/input resolution, otherwise exposing UNBOUND.

#### Scenario: New eligible source

- **WHEN** a new source matches the declared role and all required fields
- **THEN** it receives the explicit default revision binding

#### Scenario: Multiple sources

- **WHEN** two source candidates match without an explicit choice
- **THEN** the binding remains UNBOUND and no arbitrary source is selected

### Requirement: Atomic retention dependencies

Binding activation and input retention SHALL be validated atomically within device-service; removing required inputs SHALL return a dependency conflict.

#### Scenario: Disabled input

- **WHEN** a user removes storage of a field required by an active variable
- **THEN** the mutation returns conflict identifying affected variables without broadening retention silently

#### Scenario: Binding activation

- **WHEN** a binding is activated with an explicitly reviewed input policy
- **THEN** the bundle and required policy changes commit together or neither commits

### Requirement: Resumable lifecycle rollout

Bulk activation and archive SHALL expose per-source resumable outcomes and preserve referenced historical revisions.

#### Scenario: Partial rollout

- **WHEN** a source fails a bulk assignment while another succeeds
- **THEN** progress records both outcomes and retry does not duplicate successful mutations

#### Scenario: Archive

- **WHEN** a variable referenced by history is archived
- **THEN** new bindings are rejected, disable generations propagate and historical revisions remain readable

