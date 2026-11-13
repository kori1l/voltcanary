# Change: Define versioned assistant skill contracts

Archived status (2026-10-28): A1 internal contract boundary implemented and offline-verified (7/7). Archived 2026-10-28; no new implementation queue. Local v2 is now enabled explicitly, while final quality/rollout remain A5/A6.

## Why
Natural-language regex routing, overlapping prompts and implicit parameter rules currently compete with model interpretation. Provider-neutral bounded contracts must precede new runtime writers.

## What Changes
- Define assistant-v2 envelopes for turns, planner decisions, skills, context, step proposals, plan state, routing and budget.
- Define current raw input and actual prior dialogue, pending questions/choices, resource focus/events and history omissions now; A3 owns persistence and A4 owns context assembly.
- Introduce a server-owned versioned skill/intent registry with compact planner summaries and typed parameter/proposal rules.
- Separate model proposals from server authority, verification, confirmation and receipt-backed success; retain legacy readers.

## Dependencies
Existing scoped conversation, trace, reviewed execution and semantic-operation baselines. No unfinished C3 or stages5/6 completion prerequisite.

Authoritative order and delegation: [orchestration program](../../README.md#ai-assistant-orchestration-program).

## Design and impact
See [target architecture](../../../../docs/target/ai-assistant-orchestration.md) and its canonical JSON schemas/examples.
Canonical contracts live in docs/target/ai-assistant-protocol.schema.json, with positive and adversarial fixtures. Generate provider projections from the same typed contracts; provider subset limitations do not weaken local validation. Bind schema/skill versions and digest to each turn/review. Catalogue IDs come only from the current authorized registry. A skill is a reviewed application resource, not a Codex development skill or downloaded executable.

Changes affect ai-agent-service application/domain/adapters, existing conversation/provider settings/UI boundaries where relevant, and their contracts. Owning domain services retain authority. Existing history/traces/receipts are reused.

## Out of scope
No unrequested implementation or paid trials from this proposal, no fine-tuning,
semantic phrase-matching fallback, direct model mutations, fabricated telemetry,
provider credential changes, automatic publication, hidden model escalation or new mandatory agent framework.

## Acceptance
The requirements in specs/assistant-skill-contracts/spec.md and every task below require implementation and evidence. Preserve existing failed-quality records, default-off rollout and immutable legacy execution. No checkbox is completed merely by creating a proposal.
