# verified-user-identity Specification

## Purpose
TBD - created by archiving change harden-user-identity-boundary. Update Purpose after archive.
## Requirements
### Requirement: Verified identity on user APIs

User-facing services SHALL derive caller identity from a valid user access JWT and SHALL NOT authenticate or elevate a caller solely from client-supplied identity headers.

#### Scenario: Direct request with forged headers

- **WHEN** a client calls a user API directly with another user's X-User-Id, role or account headers and no valid JWT
- **THEN** authentication fails and no protected resource is returned or changed.

#### Scenario: Valid caller with conflicting headers

- **WHEN** user A supplies a valid JWT and headers naming user B or ADMIN
- **THEN** headers do not override A's verified identity and all ownership checks still apply to A.

### Requirement: Distinct authentication boundaries

Services SHALL preserve explicit user, collector and internal authentication contracts without accepting a service or source credential as a universal user credential.

#### Scenario: Collector and service credentials

- **WHEN** a collector token or shared internal token is submitted to a user creation endpoint
- **THEN** it does not grant arbitrary user identity or account access.

### Requirement: Authorized autonomous test workflows

Local and CI verification SHALL support scripted login or isolated signed JWT fixtures and scoped resource creation/cleanup without a runtime authentication bypass.

#### Scenario: Codex tests at the user's request

- **WHEN** authorized automation provisions a dedicated local test account and creates a device and private dashboard
- **THEN** normal authenticated APIs allow inspection and scoped cleanup without manual token issuance per action or trusting X-User headers.

#### Scenario: Test credentials are isolated

- **WHEN** an application runs outside the isolated test context
- **THEN** test signing keys and test-only identity injection are not automatically trusted.

### Requirement: Diagnostic access preserves authorization

Direct diagnostic access SHALL enforce the same user identity checks as gateway access, even when published locally.

#### Scenario: Local port and port-forward

- **WHEN** an operator uses a configured loopback service port or port-forward
- **THEN** valid JWT requests work and header-only impersonation remains rejected.

