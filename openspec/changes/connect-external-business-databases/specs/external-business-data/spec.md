## ADDED Requirements

### Requirement: External data without compulsory copying

The platform SHALL expose mapped authorized customer tables/views through typed datasets and bounded direct reads, creating local projections/history only for explicit subscribed requirements.

#### Scenario: Ordinary table access
- **WHEN** a user connects an allowed customer table
- **THEN** list/query operates through parameterized bounded adapter reads without cloning the database

#### Scenario: Temporal requirement
- **WHEN** a rule needs historical/deletion evidence the source does not expose
- **THEN** compilation requires an approved sufficient projection/capture contract or rejects that rule

### Requirement: Strict adapter capabilities and drift

Every adapter SHALL declare and verify key/type/query/consistency/change/write capabilities and enforce source schema fingerprint, credential isolation and allowed network/object scope.

#### Scenario: Schema drift
- **WHEN** a remote key/column type changes
- **THEN** affected mapping pauses and reports drift without silent coercion

#### Scenario: Private database route
- **WHEN** an authorized customer endpoint is private-network hosted
- **THEN** only its reviewed connector/network route is allowed and model-supplied endpoints grant no egress

### Requirement: Safe remote writes and unknown recovery

Remote writes SHALL require granted field/operation scope, CAS and atomic idempotency receipt/status semantics; unsupported sources SHALL NOT receive best-effort retried mutations.

#### Scenario: Lost commit acknowledgement
- **WHEN** a remote transaction commits but its response is lost
- **THEN** the same key is reconciled from its receipt without a second write

#### Scenario: Missing receipt facility
- **WHEN** a DB login can UPDATE but the adapter cannot prove idempotency/CAS
- **THEN** the platform refuses the unsupported mutation and preserves read capabilities



### Requirement: External locator and uncertain receipt semantics

External adapters SHALL preserve source key equality through a versioned codec, use authorized opaque locators without a full identity mirror, and distinguish IN_PROGRESS/NOT_VISIBLE from proven rejection.

#### Scenario: Revision two implementer boundary
- **WHEN** a status request cannot see a receipt while an original write may still be running
- **THEN** the owner retains UNKNOWN and reconciles the same key under verified in-flight deduplication; no new-key replay occurs
