## ADDED Requirements

### Requirement: Contextual assistant requests

The page and drawer assistant SHALL show current device/alert/interval context and allow ordinary requests without editing JSON. Raw context and internal safety diagnostics SHALL remain secondary advanced detail.

#### Scenario: An operator opens Assistant from telemetry or an alert

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the request is reviewed
- **THEN** the visible context matches the canonical route entities and interval and changing context does not silently target an unrelated device.

### Requirement: Clear plan and execution review

Plans SHALL prioritize named affected objects, proposed changes and consequences with separate read-only and mutating execution paths. Mutating actions SHALL require the existing confirmation review; bypass-style commands SHALL not be an ordinary option.

#### Scenario: A write plan is returned

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator reviews, cancels or confirms it
- **THEN** no write occurs on review/cancel, effects are understandable before confirmation and execution status/errors are tied to individual actions.

### Requirement: Capability-aware provider and model setup

Provider setup SHALL separate connection, model selection and advanced tuning, use capability-supported fields, label timeouts with understandable units and show real connection-test results.

#### Scenario: A provider with different supported features is chosen

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator configures a model
- **THEN** unsupported options are explained or disabled and canonical model/capability values are submitted without pretending a connection test succeeded.

### Requirement: Safe understandable secret rotation

Provider secret state and rotation SHALL use separate reviewed controls, indicate configured/missing status without disclosure and preserve existing encryption and authorization boundaries. Secrets SHALL not enter persisted drafts, request context or diagnostic output.

#### Scenario: A configured provider key is rotated

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator reviews and applies the replacement
- **THEN** the affected provider and connection impact are clear and subsequent UI contains only configured-state metadata.

