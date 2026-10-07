## ADDED Requirements

### Requirement: Private server-owned definitions and preferences

The platform SHALL persist typed private dashboards, immutable published revisions, author drafts and user/account preferences under server-resolved active ownership, with ten active dashboards per user and twenty-four widgets per definition.

#### Scenario: Private definition survives a session

- **GIVEN** a USER publishes a dashboard and sets it as default
- **WHEN** a new session reads preferences and definition
- **THEN** the same dashboard ID, published revision and default are returned without browser-only configuration.

#### Scenario: Foreign dashboard identifier

- **GIVEN** another user or platform ADMIN knows a private dashboard ID
- **WHEN** definition, revisions or draft are requested without resource access
- **THEN** the response is indistinguishable from missing404; supplied owner/account fields cannot grant access.

### Requirement: Draft concurrency and idempotent publication

Draft saves and publication SHALL require version preconditions and produce immutable revisions atomically; repeated create or publish with the same actor-bound idempotency key and body SHALL reuse the result after renewed authorization.

#### Scenario: Stale draft publishes

- **GIVEN** revision4 is current and a draft was based on3
- **WHEN** publish is attempted
- **THEN** 409 conflict preserves4 and the stale draft; no last-write-wins occurs.

#### Scenario: Network retry repeats publish

- **GIVEN** publication committed but the response was lost
- **WHEN** the same key/body is retried
- **THEN** the same revision is returned; a different body with that key is rejected and revoked access is not bypassed.

### Requirement: Archive copy and compatible schemas

Archive SHALL preserve dashboard identity and history, invalidate inaccessible default preferences safely, and support restore; copies SHALL be independent and exclude grants, secrets and unauthorized bindings, while unknown widget versions remain inert and preserved.

#### Scenario: Archived personal default

- **GIVEN** a default dashboard is archived
- **WHEN** the user enters the Dashboard route
- **THEN** a safe overview and explanation appear without redirect loop; restore preserves the original ID.

#### Scenario: Unknown widget type

- **GIVEN** a definition contains a newer unsupported widget version
- **WHEN** an otherwise valid draft is saved or copied
- **THEN** its safe payload is preserved as unsupported, while credentials and executable content remain forbidden.
