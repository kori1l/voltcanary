# assistant-model-routing Specification

## Purpose
TBD - created by archiving change route-ai-assistant-models-and-budgets. Update Purpose after archive.
## Requirements
### Requirement: Explicit provider-neutral model policy
The assistant SHALL select models using an authorized versioned PINNED or AUTO policy that considers skill qualification, supported contracts, request capacity and configured economic constraints without a routing-model call.

#### Scenario: Pinned model is too small
- **WHEN** a PINNED request cannot fit its required context and output reserve
- **THEN** the system returns a typed capacity outcome without silently switching provider

#### Scenario: Qualified economical operation
- **WHEN** a widget move fits a qualified economical profile
- **THEN** the policy does not require a stronger model merely because the task edits layout

#### Scenario: Conversational kernel exceeds capacity
- **WHEN** actual prior questions, retained goals, required facts, current input and instructions exceed a candidate's request capacity
- **THEN** policy chooses another authorized qualified candidate or returns a typed capacity outcome rather than silently removing mandatory dialogue; PINNED never silently switches model

### Requirement: One durable attempt and cost budget
Every provider invocation SHALL reserve against one durable turn budget and its parent-plan ceiling, including planner, continuation, repair and provider-format correction, with no nested reset.

#### Scenario: Nested repair
- **WHEN** a helper requests another correction after earlier calls
- **THEN** the same ledger is checked before network I/O and exhaustion stops the call

#### Scenario: Uncertain usage
- **WHEN** a network attempt may have consumed tokens but has no usage receipt
- **THEN** its reservation remains conservatively accounted and is not reported as free

#### Scenario: Comparable costs
- **WHEN** an economical pipeline is compared to a reference model
- **THEN** all calls, failures and retries use explicit comparable price snapshots; unknown prices prevent a savings claim

