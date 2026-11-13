# Change: Route assistant models with one shared budget

Archived status (2026-10-28): A2 internal routing/budget boundary implemented and offline-verified (9/9). Archived 2026-10-28; preserve the shared ledger and PINNED policy. This is not full provider/model qualification.

## Why
The current nested model repairs multiply calls and keep cost opaque. Economical execution and provider independence require one measurable ledger and explicit routing policy rather than a hard-coded model ranking.

## What Changes
- Implement PINNED/AUTO model policies by skill, phase, qualified capabilities, complete request size, price and latency constraints.
- Own the sole reservation/usage ledger across planner, steps, context continuations, repair and adapter format correction.
- Normalize provider proposals, usage and typed failures; support qualified JSON-text providers without requiring native function calling.

## Dependencies
define-ai-assistant-skill-contracts. Reuses existing provider registry, credential handling, model adapters and AssistantTraceRecorder.

Authoritative order and delegation: [orchestration program](../../README.md#ai-assistant-orchestration-program).

## Design and impact
See [target architecture](../../../../docs/target/ai-assistant-orchestration.md) and its canonical JSON schemas/examples.
Routing is deterministic application policy, not another LLM classification call. Small-model qualification and decomposition precede escalation. Reserve calls, tokens, time and price before network I/O with optimistic concurrency and a parent-plan ceiling. Fail closed for missing authority, ledger writes or strict-price information. Unknown usage remains charged/reserved conservatively. Currency/rate snapshots, cached-input and reasoning subset accounting are explicit. This change supplies the budget; repair-ai-dashboard-planning-failures consumes it and must not add another coordinator ledger.

Changes affect ai-agent-service application/domain/adapters, existing conversation/provider settings/UI boundaries where relevant, and their contracts. Owning domain services retain authority. Existing history/traces/receipts are reused.

## Out of scope
No unrequested implementation or paid trials from this proposal, no fine-tuning,
semantic phrase-matching fallback, direct model mutations, fabricated telemetry,
provider credential changes, automatic publication, hidden model escalation or new mandatory agent framework.

## Acceptance
The requirements in specs/assistant-model-routing/spec.md and every task below require implementation and evidence. Preserve existing failed-quality records, default-off rollout and immutable legacy execution. No checkbox is completed merely by creating a proposal.
