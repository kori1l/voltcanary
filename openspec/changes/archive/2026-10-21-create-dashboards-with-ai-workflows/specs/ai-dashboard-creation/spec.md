## ADDED Requirements

### Requirement: Server-owned template compilation

AI SHALL select a typed dashboard intent and the server SHALL assemble definitions from existing authoritative templates and manifests.

#### Scenario: A small model selects a template

- **WHEN** a valid intent names a supported template and authorized equipment
- **THEN** the server builds and validates the definition without requiring the model to generate layout or query code.

### Requirement: Authorized compatible bindings

Preparation and creation SHALL verify all bindings through existing domain authorization and metric capabilities without treating dashboard creation as data access permission.

#### Scenario: Mixed allowed and foreign devices

- **WHEN** a request contains both the caller's device and a foreign inaccessible device
- **THEN** preparation or creation fails without disclosing foreign data or silently dropping that device.

#### Scenario: Missing metric versus missing samples

- **WHEN** a requested metric is unsupported or has no samples
- **THEN** unsupported binding requires a user choice while a valid empty series remains explicit no-data rather than zero or fabricated evidence.

### Requirement: Reviewed private draft creation

The assistant SHALL create only the reviewed private draft through the ordinary authenticated dashboard API and SHALL NOT publish, share or change the default dashboard implicitly.

#### Scenario: Review is cancelled or template changes

- **WHEN** preview is cancelled or template semantics change after review
- **THEN** no new dashboard is created until the current payload is prepared and confirmed.

### Requirement: Reliable creation and ongoing authorization

Creation SHALL reuse domain idempotency and normal widget reads SHALL continue to check current access independently of the creating task.

#### Scenario: Lost response followed by retry

- **WHEN** dashboard creation commits and execution retries after restart
- **THEN** authorized reconciliation returns the same draft instead of creating another dashboard.

#### Scenario: Access revoked after creation

- **WHEN** a previously valid binding loses authorization before a widget read
- **THEN** neither the stored dashboard nor a cached AI result grants continued protected data access.

### Requirement: Small-model acceptance evidence

Delivery SHALL include versioned Russian intent fixtures and recorded evaluation against the chosen small model using the declared thresholds.

#### Scenario: Provider unavailable or unsuitable

- **WHEN** live evaluation cannot run or the model fails the frozen criteria
- **THEN** evidence reports blockage or failure without substituting another model or declaring the workflow accepted.
