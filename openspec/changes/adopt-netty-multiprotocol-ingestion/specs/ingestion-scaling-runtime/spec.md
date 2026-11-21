## ADDED Requirements

### Requirement: Event Transport Rollout Preserves Single Active Ownership

Transport rollout and rollback SHALL select one active generation per endpoint
or source cohort, preserve shared correctness state and connector fences, and
prevent duplicate old/new effect paths. MQTT replica subscription/session topology
SHALL explicitly avoid duplicate ordinary subscriptions and client identity eviction.

#### Scenario: MQTT consumption moves between old and new transports

- **WHEN** the source cohort changes transport generation
- **THEN** old ownership SHALL stop/fence before new delivery becomes active
- **AND** pending/unknown outcomes and persistent-session replay SHALL be reconciled
- **AND** shadow comparison SHALL NOT publish or act as another active ACK owner.

#### Scenario: A connector lease transfers with queued work

- **WHEN** the old owner loses its lease while work is offloaded
- **THEN** ownership generation SHALL remain attached through callback/publication boundaries
- **AND** stale work SHALL be rejected under the existing qualified fencing guarantees.

### Requirement: Channels Drain Within Explicit Lifecycle Bounds

Readiness SHALL require usable source config and required processing resources.
Shutdown SHALL stop new work, drain bounded in-flight work and close connections
before terminating their owned executors/groups. Existing channels SHALL NOT be
assumed to migrate automatically when replicas scale out.

#### Scenario: A replica drains during mixed ingress

- **WHEN** readiness is removed for shutdown or rollback
- **THEN** new HTTP admission and active connector scheduling SHALL stop according to policy
- **AND** MQTT/subscription handover SHALL preserve the qualified retry/session contract
- **AND** remaining work SHALL complete or receive explicit failed/unknown disposition within the drain deadline
- **AND** one adapter SHALL NOT terminate a group still owned by another mode.
