# Backend Architecture

## Purpose

Define backend service structure and dependency rules for VoltCanary services.
## Requirements
### Requirement: Services Use Hexagonal Boundaries

Backend services SHALL separate domain logic, application use cases, and adapter or infrastructure responsibilities.

#### Scenario: New backend service is created

- **WHEN** a new backend service is added
- **THEN** it SHALL define clear domain, application or service, and adapter or infrastructure responsibilities
- **AND** business decisions SHALL NOT live in HTTP controllers, message consumers, repositories, or external client implementations.

#### Scenario: Existing service is extended

- **WHEN** an existing backend service receives new behavior
- **THEN** the change SHALL follow the service's existing package conventions
- **AND** new business decisions SHALL be placed in domain or application/service code rather than transport or persistence adapters.

### Requirement: Inbound Adapters Delegate To Application Logic

Inbound adapters SHALL translate external input and delegate use-case execution to application or service-layer code.

#### Scenario: HTTP controller handles a command

- **WHEN** an HTTP controller receives a command request
- **THEN** it SHALL validate and map transport DTOs
- **AND** delegate business behavior to an application service, use case, or established service-layer component
- **AND** avoid implementing domain decisions directly.

#### Scenario: Message consumer handles an event

- **WHEN** a Kafka or other message consumer receives an event
- **THEN** it SHALL deserialize and validate the message boundary
- **AND** delegate processing to application or service-layer code
- **AND** keep retry, idempotency, and dead-letter behavior explicit at the adapter boundary.

### Requirement: Outbound Integrations Use Explicit Adapter Boundaries

Outbound access to persistence, messaging, HTTP services, object storage, ML services, and external SDKs SHALL be isolated behind repositories, clients, ports, or adapter services.

#### Scenario: Application logic needs persisted data

- **WHEN** application logic needs to read or write persisted data
- **THEN** it SHALL use a repository or persistence port
- **AND** persistence queries SHALL NOT be embedded in controllers or unrelated transport adapters.

#### Scenario: Application logic calls another service

- **WHEN** application logic needs another backend service, ML service, object store, or external API
- **THEN** it SHALL depend on an explicit client, port, or adapter service
- **AND** protocol-specific details such as HTTP paths, Kafka producer APIs, SDK calls, and serialization SHALL remain outside domain code.

### Requirement: Domain Code Avoids Framework And Infrastructure Dependencies

Domain code SHALL stay focused on business concepts and rules.

#### Scenario: Domain model is implemented

- **WHEN** domain classes, value objects, or domain services are implemented
- **THEN** they SHALL avoid dependencies on Spring MVC controllers, Kafka clients, HTTP clients, repositories, external SDKs, and generated transport DTOs.

#### Scenario: JPA entity is used as domain model

- **WHEN** a service keeps JPA annotations on domain entities
- **THEN** repositories SHALL still be treated as outbound persistence adapters
- **AND** controllers and message consumers SHALL NOT depend on repositories directly for business workflows.

### Requirement: Architecture Verification Remains Repeatable

Backend structure rules SHALL be documented and checked with repeatable validation where practical.

#### Scenario: Project structure check is run

- **WHEN** `scripts/check-project-structure.ps1` is executed
- **THEN** package declarations, controller placement, repository placement, entity placement, production injection style, and empty source package rules SHALL be checked consistently.

#### Scenario: Architecture rule changes

- **WHEN** backend architecture conventions are changed
- **THEN** `openspec/project.md`, this specification, relevant `docs/architecture` or `docs/adr` files, and structure checks SHALL be updated together when applicable.

### Requirement: Analytics ML Services Are Use-Case Scoped

Analytics-service ML application services SHALL keep independent lifecycle
workflows in separate use-case components.

#### Scenario: Dataset lifecycle behavior is extended

- **GIVEN** analytics-service owns ML dataset preview, validation,
  materialization, listing, detail, archive, and delete workflows
- **WHEN** one of those workflows changes
- **THEN** the implementation SHALL route the behavior through a focused
  collaborator for that workflow
- **AND** SHALL NOT add more preview, materialization, lifecycle, hashing, and
  DTO mapping behavior to a single all-purpose dataset service.

#### Scenario: Model deployment behavior is extended

- **GIVEN** analytics-service owns model approval, model lifecycle, deployment
  policy changes, policy resolution, and audit
- **WHEN** deployment behavior changes
- **THEN** policy resolution and lifecycle commands SHALL remain in distinct
  components
- **AND** model registry facades SHALL stay thin if retained for controller
  compatibility.

### Requirement: Recalculation Orchestration Delegates Decisions

Analytics recalculation SHALL keep job orchestration separate from calculators,
ML request construction, fallback decisions, persistence, and event publishing.

#### Scenario: ML fallback behavior changes

- **WHEN** analytics-service changes how it handles unavailable ML-service or
  incompatible model features
- **THEN** fallback selection and traceability persistence SHALL be implemented
  behind explicit collaborators
- **AND** the recalculation entry point SHALL remain an orchestrator rather than
  a combined calculator, adapter, serializer, and repository owner.

### Requirement: Alert Notifications Use An Internal Module Boundary

Alert-service notification delivery SHALL remain behind an internal boundary
while it is hosted in alert-service.

#### Scenario: Alert lifecycle requests notification delivery

- **WHEN** alert lifecycle code creates or escalates an alert
- **THEN** it SHALL request delivery through an alert notification port
- **AND** pass an immutable notification request snapshot rather than a JPA
  alert entity.

#### Scenario: Notification provider delivery is extended

- **WHEN** a notification provider channel is implemented
- **THEN** provider-specific delivery SHALL live behind an adapter selected by
  notification channel type
- **AND** retry, lease, idempotency, preference, channel, and attempt state
  transitions SHALL remain in the notification application module.
