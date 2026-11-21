# Change: Recover dashboard planning failures with one bounded policy

**2026-11-03 configurable-platform alignment:** New unimplemented configured-resource scope follows [VCL](../../../docs/target/configurable-operations-language.md), [storage/adapters](../../../docs/target/configurable-operations-storage.md), [strict imports](../../../docs/target/configurable-operations-imports.md) and [acceptance](../../../docs/target/configurable-operations-acceptance.md). Preserve existing implemented behavior, checked tasks, immutable reviews and historical quality evidence. Use the [current implementation order](../README.md#ai-first-execution-priority) for configurable scope. Live evaluation follows the owning change’s authorization and qualification gates.


Current status (2026-10-28): R owns resilience/recovery (0/15 tasks checked; partial recovery wiring exists). Implement it at the end of A5, after the no-repair/no-model-judge first-pass checkpoint; final evidence belongs to A6.

## Why
The application already repairs some malformed drafts in ActionPlanner and retries
preparation in CreationTaskPlanning. These loops can nest, diagnostics are often
plain strings and exhausted technical failures can resemble a user clarification.
The user should not be asked to repair widget schemas or lose pending work.

## What Changes
- Integrate dashboard retries with A2's sole application budget/provider boundary and
  typed recovery categories; do not build a second ledger or provider-call coordinator.
- Repair deterministic compatible mistakes in code; use bounded policy-selected repair
  for operation/preparation/outcome failures with real diagnostics and retained goals.
- Separate genuine user ambiguity from technical failure, unavailable data, access
  denial, provider failure and already-satisfied work.
- Persist safe attempt correlation/outcomes, preserve the accepted review and keep
  retry distinct from confirmed domain execution and UNKNOWN reconciliation.
- Run final repeated conversation/browser/execution acceptance before enabling
  the new semantic planning path by default.

## Dependencies

2026-11-11: [AI-first/library migration](../README.md#ai-first-execution-priority)
uses the same graph for bounded repair transitions. R owns recovery policy,
taxonomy and UX; library auto-retry/tool loops cannot bypass the existing A2
ledger or original first-pass-before-final-V/R gate. Basic graph adoption does
not depend on full R archival or qualified live recovery scores.
2026-10-28: R is the final A5 resilience package after core workflow first-pass
measurement, not a prerequisite for skill/context improvements. Diagnostic runs
disable automatic correction and model judges; A6 compares first-pass and recovered
results separately under predeclared limits. This amendment starts no live run.
Amended2026-10-26: follow the [orchestration program](../README.md#ai-assistant-orchestration-program).
Reuse completed C1/C2. A1–A4 supply contracts, the sole shared ledger/routing service,
durable plans and skill context. Verifier contracts/offline checks supply typed
postcondition diagnostics; complete recovery integration/offline tests for A5.
Full C3/verifier archival is not required first. A6 supplies shared live acceptance
and rollout evidence afterward. Retain section11/12 preview traceability and do not
rebuild history/traces, the A2 ledger, or another nested retry mechanism.

## Ownership and impact
ai-agent-service coordinates policy via existing model adapters, run recorder,
task state and preparation adapters. Frontend renders genuine pending/question/
failure outcomes and retains the previous preview. Dashboard-service retains
definition/access authority and ordinary mutation receipts remain unchanged.

## Out of scope
No hidden provider/model switching (explicit AUTO policy is owned by A2), infinite retries, chain-of-thought logging,
new mandatory framework, AI-created missing sources, automatic publication or
mutation retries disguised as planning repair.

## Acceptance
Recoverable schema/type/outcome faults are repaired within a single measurable
budget without repeated name questions or collateral changes. Genuine ambiguity
asks one understandable question. Exhaustion returns an honest retriable failure
with preserved task state and no READY candidate. The complete original tram
conversation and release corpus pass the documented repeated-run gates before
default rollout; a blocked live provider cannot be counted as acceptance.
