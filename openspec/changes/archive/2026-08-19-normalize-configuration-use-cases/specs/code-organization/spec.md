# Code Organization Specification

## ADDED Requirements

### Requirement: Runtime Configuration Is Isolated

Runtime configuration SHALL be represented as typed properties or bean wiring in
configuration packages, not as growing business services.

#### Scenario: Runtime property is introduced

- **WHEN** a service needs a new environment variable, profile toggle, endpoint,
  timeout, or integration credential reference
- **THEN** the value SHALL be bound through a typed configuration object in a
  `config` package or equivalent framework-native settings module
- **AND** business decisions based on that value SHALL remain in a domain,
  application, or adapter component with an explicit responsibility.

### Requirement: User-Managed Settings Use Business Names

Persisted user-managed settings SHALL be modelled as policy, preference, or
use-case services with names that describe the owned business decision.

#### Scenario: Device analytics settings are extended

- **WHEN** behavior is added to a user-managed analytics, ML, simulator, or AI
  model setting
- **THEN** the implementation SHALL make the business concept explicit in class
  or module names
- **AND** SHALL NOT add unrelated validation, defaulting, serialization,
  persistence, and workflow orchestration to a generic `ConfigService` or
  `SettingsService` class.

### Requirement: Large Units Are Split By Responsibility

Production classes and modules SHALL remain reviewable by keeping independent
workflows in separate components.

#### Scenario: A service grows beyond one use case

- **WHEN** a backend service, Python service module, or frontend page owns
  independent workflows such as preview, validation, materialization, lifecycle
  cleanup, rendering, polling, artifact I/O, or DTO mapping
- **THEN** the next implementation change SHALL split those responsibilities
  into focused collaborators or hooks
- **AND** keep any compatibility facade thin and covered by tests.

#### Scenario: A new feature is added to an already large unit

- **GIVEN** an existing production file is already above the agreed guardrail
  threshold for its surface
- **WHEN** a feature change needs to touch that file
- **THEN** the change SHALL either extract a focused collaborator first or
  explicitly record why extraction is deferred in its OpenSpec tasks.

### Requirement: Code Organization Checks Are Repeatable

The repository SHALL provide a repeatable way to detect boundary drift before it
becomes the default implementation pattern.

#### Scenario: Project structure check runs

- **WHEN** the project structure check is executed
- **THEN** it SHALL report or fail on configured architecture risks such as
  oversized production services, generic configuration-service names, wildcard
  imports in production code, and excessive constructor dependencies
- **AND** exemptions SHALL be documented rather than silently ignored.
