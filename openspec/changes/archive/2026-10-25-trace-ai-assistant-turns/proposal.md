# Change: Persist observable assistant steps for diagnosis

## Why
Provider runs marked SUCCESS only prove an API call returned. The failed bus
dashboard lost its selected device and invented dataRefs, but current records do
not retain every preparation attempt or the first rejected device fields.

## What Changes
- Persist bounded, correlated lifecycle events for context resolution, capability
  reads, model calls, validation, repair, review, execution and reconciliation.
- Record safe structured inputs/outputs, actual check failures and timing so every
  attempt can be inspected, including failures before a review exists.
- Provide owner-scoped diagnostic details/export and honest progress from emitted events.

## Dependencies and scope
Requires persist-ai-assistant-conversations. Runs second, before context corrections
and dashboard stages5/6 so their acceptance has usable evidence. Reuse existing model
runs/audit and task IDs. This change owns storage/inspection, not a new retry policy;
stage6 remains the owner of global repair budgets and rollout quality gates.

## Acceptance
An intentionally failed bus dialogue identifies where selection disappeared, which
capabilities were read, each rejected candidate/check and the final outcome. Export
contains no credentials or private model reasoning. Provider success, planning success
and execution success are separate states.
