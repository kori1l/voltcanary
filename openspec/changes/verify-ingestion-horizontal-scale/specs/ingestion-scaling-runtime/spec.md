# Ingestion Scaling Runtime

## ADDED Requirements

### Requirement: Horizontal Scale Verification Covers Push And Active Sources

VoltCanary SHALL provide repeatable verification that ingestion can run with
multiple replicas while preserving push-ingress and active-connector correctness.

#### Scenario: Multi-replica smoke runs

- **WHEN** the horizontal ingestion smoke starts at least two ingestion-service
  replicas with shared Kafka and Redis
- **THEN** push telemetry SHALL be accepted through any healthy replica
- **AND** duplicate detection, rate limiting, source config reads, connector
  ownership, and Kafka keying SHALL be verified across replicas.

#### Scenario: Active connector owner fails

- **WHEN** the replica that owns an active connector source stops or loses its
  lease
- **THEN** another eligible replica SHALL acquire the source after the expected
  failover interval
- **AND** the source SHALL NOT be polled or subscribed by two replicas at the
  same time.

### Requirement: Horizontal Deployment Behavior Is Documented

Horizontal ingestion operation SHALL be documented with required shared
dependencies, profiles, health expectations, and troubleshooting steps.

#### Scenario: Operator runs ingestion horizontally

- **WHEN** an operator or agent follows the horizontal ingestion deployment docs
- **THEN** the required Kafka, Redis, source runtime config, connector lease,
  and health-check configuration SHALL be clear
- **AND** known failure modes SHALL identify whether ingestion should reject,
  degrade, retry, or continue from local read model state.
