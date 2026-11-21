## ADDED Requirements

### Requirement: Dense optional-feature storage and strict data mutation

Native datasets SHALL use server-provisioned dense typed SQL tables with inline reference values, optional history/projection and explicitly selected indexes; ordinary no-relation reads SHALL NOT reconstruct scalar values through EAV or mandatory relation joins. Dataset mutation/import SHALL use the normative sealed manifest, CAS and idempotency contracts.

#### Scenario: Simple table
- **WHEN** a dataset uses no relations or history
- **THEN** its normal list/read is a single dense data-table query and unselected features add no edge/history rows

#### Scenario: Explicit sealed query
- **WHEN** an authorized actor seals a bounded query and later reads the same SNAPSHOT selection
- **THEN** every page uses the captured result and cutoff after current access checks, and a changed selection or expired snapshot fails explicitly

#### Scenario: Stale import
- **WHEN** target rows or schema change after validation
- **THEN** apply rejects the stale manifest rather than overwriting current data


### Requirement: Configurable typed records

Companies SHALL define typed collections, fields and references through reviewed versioned metadata without new domain-specific Java tables/controllers or user-controlled physical DDL.

#### Scenario: New customer concept
- **WHEN** a company defines an asset-linked customer/order collection
- **THEN** the platform validates and persists its schema/records through generic APIs while existing resource ownership stays authoritative

#### Scenario: Breaking schema
- **WHEN** a field unit or relation target changes
- **THEN** dependent configuration is identified and migration requires review rather than silently reinterpreting old records

### Requirement: Temporal authorized relations

Bounded lookups SHALL enforce field/row scope and explicit cardinality and effective-time semantics, preserving the exact revisions used.

#### Scenario: Reassignment
- **WHEN** a person changes vehicles during a work interval
- **THEN** the join can split at assignment boundaries using the actual effective relations

#### Scenario: Ambiguous or foreign relation
- **WHEN** multiple incompatible assignments or an inaccessible referenced row exists
- **THEN** the result is UNKNOWN or denied without first-match guessing or leaking counts

#### Scenario: Event time at a sealed data version
- **WHEN** a lookup uses the key fields and interval declared when a query snapshot was sealed
- **THEN** it resolves the exact match at the event time using the captured data version, with the interval start included and end excluded



### Requirement: Native identity and generation safety

Native persistence SHALL implement declared composite keys, omitted-versus-NULL updates, stable reserved row IDs and generation-fenced replacement without lost commits.

#### Scenario: Revision two implementer boundary
- **WHEN** a staged replacement overlaps a writer on the old generation
- **THEN** head locking orders the operations or rejects stale replacement; matching keys preserve row IDs
