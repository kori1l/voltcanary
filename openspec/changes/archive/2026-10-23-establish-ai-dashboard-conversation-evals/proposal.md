# Change: Establish reproducible dashboard conversation evaluations

## Why
The reported failure is a conversation that deteriorates after several edits.
Existing unit tests, the template-oriented `ai-dashboard-model-evaluation.ps1`,
layout fixtures and one successful settings refinement do not measure this failure
reliably. Twenty-one assertions over one response are one trial, not21 trials.

## Current baseline
Reuse the current authenticated gateway, ordinary model-provider configuration,
dashboard fixture/smoke scripts and evaluation resources in ai-agent-service.
Existing test and Docker evidence remains valid for the cases actually exercised.
Do not replace historical template fixtures with new expectations silently.

## What Changes
- Add a versioned Russian multi-turn corpus based on the actual bus/tram dialogue,
  with deterministic outcome assertions after each turn and isolated fixture state.
- Extend evaluation tooling to call the real planning/clarification/recovery path,
  measure repeated whole conversations and retain sanitized per-turn diagnostics.
- Separate baseline failures, product regressions, provider failures and unavailable
  infrastructure; none may masquerade as successful model behavior.
- Establish a held-out set and release gates for the subsequent reliability changes.

## Scope and ownership
scripts and ai-agent-service test resources own the runner and corpus; Java tests
own deterministic state/compiler checks; frontend tests and browser acceptance own
visible rendering and history interaction. Existing production APIs are exercised,
not replaced by a test-only planner. No new production endpoint, dependency or DB
migration is required by this change.

## Dependencies and delivery
First stage of the [reliability program](../../README.md#ai-dashboard-reliability-program).
Requires the current authenticated reviewed-task and shared preview baseline.
This stage is complete when the harness and an honest baseline report exist, even
if known quality cases fail. Default rollout of the later architecture requires
the final quality gates, not merely completion of this stage.

## Out of scope
No prompt/compiler behavior changes, automatic model replacement, fine-tuning,
production data mutation, production provider credential collection or load testing.

## Acceptance
The same runner can reproduce a failure, identify its first failing turn, detect
an intentionally corrupted outcome and run without persisting credentials. It
records exact model/prompt/schema/fixture versions and counts conversations, trials
and assertions separately. Required live gates cannot pass with mocks or missing
provider access. Existing historical evidence is not relabelled as new acceptance.
