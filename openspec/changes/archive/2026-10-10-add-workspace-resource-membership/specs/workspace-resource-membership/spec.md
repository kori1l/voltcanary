## ADDED Requirements

### Requirement: Explicit collaboration preserves personal ownership

Workspaces SHALL have validated active members and teams with explicitly consented resource-account association; existing personal account ownership SHALL remain compatible and SHALL NOT be merged or transferred merely by joining a workspace.

#### Scenario: Personal owner joins a workspace

- **GIVEN** a user already owns private devices
- **WHEN** membership is established
- **THEN** devices remain privately owned until explicit authorized resource association and grants are configured.

#### Scenario: Foreign account association

- **GIVEN** a manager submits another account ID without owner consent
- **WHEN** resource association is requested
- **THEN** the request is rejected without exposing private resource inventory.

### Requirement: Resource action grants are independent from identity roles

Workspace access SHALL default deny and distinguish device, telemetry, analytics, incident, maintenance, ML and future tariff capabilities by authorized resource scope; platform ADMIN or SUPPORT roles SHALL NOT grant universal business-data access.

#### Scenario: Viewer without incident write

- **GIVEN** an active member can read telemetry but cannot acknowledge incidents
- **WHEN** Ack is requested through a direct API
- **THEN** the command is forbidden even if the member can edit a dashboard.

#### Scenario: Service technician in two clients

- **GIVEN** the technician belongs to client A only
- **WHEN** a client B resource UUID is submitted
- **THEN** B resource access is denied and membership in A is not transitive.

### Requirement: Current revocation and downstream lifecycle enforcement

Downstream reads and commands SHALL recheck current membership, account lifecycle and grants, using a versioned authorization fingerprint to invalidate cached access without relying solely on JWT expiry or asynchronous events.

#### Scenario: Revoked member holds token

- **GIVEN** a member still has a valid access JWT and cached panel data
- **WHEN** membership is revoked
- **THEN** the next request is forbidden, no previous cache values are served and the access change is audited.

#### Scenario: Owner departure

- **GIVEN** the workspace owner must leave
- **WHEN** departure is requested
- **THEN** an authorized reviewed transfer or explicit orphan handling is required rather than preserving departed-owner access.
