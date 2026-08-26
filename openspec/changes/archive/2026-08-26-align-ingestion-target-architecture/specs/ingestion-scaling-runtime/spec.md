# Ingestion Scaling Runtime

## ADDED Requirements

### Requirement: Connector Ownership Is Fenced For Future Commands

Connector runtime ownership SHALL expose fencing metadata that can distinguish
current ownership from stale ownership after failover.

#### Scenario: Active connector ownership changes

- **WHEN** another ingestion instance acquires a source after lease expiry,
  release, or failover
- **THEN** the new ownership SHALL have a fencing token or generation different
  from the stale owner
- **AND** renew, release, polling publication, and subscription forwarding
  checks SHALL reject stale ownership.

#### Scenario: Command-capable adapter is introduced

- **WHEN** a future adapter adds command execution capability
- **THEN** command execution SHALL require current fenced connector ownership
  for stateful source connections
- **AND** owner identity alone SHALL NOT be sufficient authorization to execute
  a command through a stateful connector.
