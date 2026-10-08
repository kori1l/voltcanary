# account-auth-console-experience Specification

## Purpose
Define task-focused authentication, recovery, personal security and reviewed administration/session workflows using existing identity contracts.
## Requirements
### Requirement: Task-focused authentication

Login and registration SHALL use a common shell with a real task heading, restrained branding, permanent labels, password visibility, password rules before submission, Enter support and duplicate-submit prevention.

#### Scenario: A user enters credentials

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the form submits or validation fails
- **THEN** busy and local errors are clear, values are retained safely and the task heading is more prominent than supporting branding.

### Requirement: Guided recovery and verification

Recovery and verification SHALL explain the next step, accept incoming token links, preserve a manual-entry fallback, distinguish expired or invalid tokens and offer repeat request and sign-in navigation. Forgotten-password outcomes SHALL not reveal whether the email exists.

#### Scenario: A recovery or verification link is invalid

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the user opens or submits it
- **THEN** a readable invalid/expired state and retry path appear; no raw token is exposed in UI diagnostics, telemetry or persisted drafts.

### Requirement: Unified profile and security settings

Personal settings SHALL separate profile, security and notifications into readable sections and use the same labeled password workflow wherever exposed. Password forms SHALL remain inside their cards with clear success and error feedback.

#### Scenario: A password change is edited at desktop or mobile width

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the user reviews or submits the form
- **THEN** all fields and the action remain visible and the result is tied to the affected form rather than an unrelated page banner.

### Requirement: Explicit administration actions

Admin SHALL be a distinct user-access surface with searchable stable user rows, named role/status controls, secondary identity details and explicit confirmed Apply actions. Server authorization SHALL remain authoritative for USER, SUPPORT and ADMIN.

#### Scenario: An administrator changes a role or status draft

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the administrator reviews and applies the action
- **THEN** the dialog identifies the user and resulting access before mutation, and cancellation leaves server state unchanged.

### Requirement: Readable session operations

Session details SHALL present status, creation, expiry and available context before raw IDs; revoke controls SHALL name the affected user and session scope with clear consequences and result feedback.

#### Scenario: An administrator inspects sessions

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** revoke is requested
- **THEN** the affected scope is shown and confirmed without exposing access or refresh token values.

### Requirement: Safe return routes and coordinated session recovery

Authentication recovery SHALL preserve a validated relative application route, coordinate simultaneous refresh attempts, and transition to expired-session login once when refresh is definitively rejected.

#### Scenario: Concurrent expiry

- **GIVEN** several independent reads return 401
- **WHEN** the access token must be refreshed
- **THEN** one refresh is performed, waiting reads share its outcome and successful login restores device metric interval context.

#### Scenario: Unsafe return destination

- **GIVEN** returnTo points to //external.example or an authentication loop
- **WHEN** login completes
- **THEN** the application ignores that destination and uses a safe internal default.

### Requirement: Recovery guidance matches configured delivery

Recovery screens SHALL explain available configured delivery and token fallback without asserting an email was delivered when that result is unknown.

#### Scenario: Delivery unavailable

- **GIVEN** a reset request uses a disabled or unconfirmed delivery configuration
- **WHEN** a response is displayed
- **THEN** the user receives accurate next steps without fabricated delivery success or disclosure of account existence.

