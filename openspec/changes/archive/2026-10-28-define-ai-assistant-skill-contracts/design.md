# Design: Define versioned assistant skill contracts

The canonical design, request/response/state formats, trust boundaries and migration
rules are in [the target architecture](../../../../docs/target/ai-assistant-orchestration.md). Keep schemas there authoritative
rather than copying provider-specific variants into this change.

## Specific implementation boundary
Canonical contracts live in docs/target/ai-assistant-protocol.schema.json, with positive and adversarial fixtures. Generate provider projections from the same typed contracts; provider subset limitations do not weaken local validation. Bind schema/skill versions and digest to each turn/review. Catalogue IDs come only from the current authorized registry. A skill is a reviewed application resource, not a Codex development skill or downloaded executable.

## Conversational contracts and implementation boundary

A1 defines the canonical current raw message and actual prior dialogue, pending
question/choices/identity, replyToQuestionId, focus, resource chronology and history
completeness/cursor. A3 owns persistence and cross-plan resource retention; A4
owns bounded projection and registered history loading. Do not implement their
runtime early to demonstrate the contracts. Repository-owned contract fixtures
must run in CI independently of ignored initial documentation-check artifacts.
Schema validity does not prove pronoun resolution, access or domain semantics.

## Prerequisites and sequencing
Existing scoped conversation, trace, reviewed execution and semantic-operation baselines. No unfinished C3 or stages5/6 completion prerequisite.

Use the [program index](../../README.md#ai-assistant-orchestration-program) for the
noncyclic order and delegated legacy criteria. Implement readers/contracts before
new writers; opt-in generation stays disabled until integration and qualification.

## Validation
Use offline schema, domain and compatibility checks first. Any real-model evidence
belongs to an explicitly authorized capped qualification campaign. Record all
actual calls in the existing trace and shared budget. Apply relevant existing
Java/frontend/connector checks, structure and strict OpenSpec validation; do not
run ML/load testing for this architecture unless that surface actually changes.
