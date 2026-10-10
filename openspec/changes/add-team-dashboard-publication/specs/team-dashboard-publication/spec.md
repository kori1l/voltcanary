## ADDED Requirements

### Requirement: Independent dashboard and data permissions

Shared dashboards SHALL distinguish view, edit, publish, manage-sharing and archive grants, require active same-workspace recipients, and SHALL NOT grant data or action access merely through dashboard sharing.

#### Scenario: Editor cannot publish

- **GIVEN** a member has dashboard edit but not publish or incident write
- **WHEN** publication or Ack is requested
- **THEN** both operations are forbidden while authorized draft editing remains possible.

#### Scenario: Foreign sharing target

- **GIVEN** a dashboard belongs to workspace A
- **WHEN** an audience from workspace B is submitted
- **THEN** sharing is rejected and no foreign private metadata is disclosed.

### Requirement: Sharing previews and viewer subsets are honest

Sharing preview SHALL report widget usability without granting resources, and each viewer query SHALL expose only their current authorized subset with its own denominator and no hidden-resource names or counts.

#### Scenario: Two viewers with different equipment access

- **GIVEN** a shared dashboard references a larger fleet
- **WHEN** viewers query with different resource grants
- **THEN** each receives its own authorized subset with scope explained in panel information and filters, with compact indicators for partial or unavailable evidence; hidden fleet totals or names cannot be inferred from supplied metadata and normal scope text does not require a permanent footer.

#### Scenario: Grant revoked during shared display

- **GIVEN** a viewer previously read widget values
- **WHEN** their membership or resource grant is revoked
- **THEN** the next query and cache serve deny access and the view clears previously displayed values.

### Requirement: Shared publication keeps personal context and safe copies

Shared defaults SHALL change only by explicit authorized publication; personal URL filters and preferences SHALL remain user-specific, while copies and exports SHALL exclude grants, evidence values, secrets and unauthorized bindings.

#### Scenario: Viewer changes shared time

- **GIVEN** a shared published dashboard has defaults
- **WHEN** one viewer applies another interval
- **THEN** another viewer and the published revision remain unchanged.

#### Scenario: Copy shared dashboard

- **GIVEN** an authorized viewer copies a permitted shared dashboard
- **WHEN** the private draft is created
- **THEN** it receives a new ID, no shared ACL, and rebinding where original resources are not permitted.

### Requirement: Audited ownership and authenticated kiosk

Sharing, revocation, publication, archive, rollback and ownership transfer SHALL be audited without raw widget values; kiosk views SHALL remain authenticated read-only displays with visible stale timing.

#### Scenario: Workspace owner departure

- **GIVEN** a dashboard owner leaves the workspace
- **WHEN** an authorized manager transfers ownership
- **THEN** the event records actor and new owner, former access is revoked and no anonymous link is created.

#### Scenario: Kiosk opens an incident panel

- **GIVEN** an authenticated kiosk displays a shared dashboard
- **WHEN** incident actions are rendered
- **THEN** no mutating command or editor control is available while timing and connectivity remain visible.
