# Change: Load bounded assistant skill context on demand

Archived status (2026-10-28): A4 internal context boundary implemented and offline-verified (8/8); archived 2026-10-28. Archived 2026-10-28; remaining production workflow quality is A5 and final qualification A6, not a restart of A4.

## Why
Small models cannot reliably use all platform instructions and catalogues at once. A current step needs a bounded relevant context, actual scoped facts and an honest way to request missing information.

## What Changes
- Implement versioned skill loading and minimal phase/step context projections rather than whole-transcript or all-skills prompts.
- Include current raw text, relevant actual prior messages and the exact pending question/choices; preserve mandatory conversational context, disclose omissions and support registered scoped history loading.
- Load actual selected-domain capabilities and retain provenance/availability; support constrained context paging.
- Supply field requirements and permitted proposals for every parameter through contracts instead of natural-language regex extraction.

## Dependencies
define-ai-assistant-skill-contracts, route-ai-assistant-models-and-budgets and plan-ai-assistant-intents-with-durable-steps.

Authoritative order and delegation: [orchestration program](../../README.md#ai-assistant-orchestration-program).

## Design and impact
See [target architecture](../../../../docs/target/ai-assistant-orchestration.md) and its canonical JSON schemas/examples.
Server-owned skill instructions and untrusted resource/user data are distinct. A model sees only required facts, current choices, base review, pending goals and availability. Each snapshot identifies asOf/version/completeness. NEED_CONTEXT can invoke only registered typed loaders with scoped opaque cursors, never arbitrary URLs/SQL. Another model continuation consumes the same budget. Mandatory instructions/goals cannot disappear under truncation. Do not call a summarizer by default or persist hidden reasoning. New context is reauthorized, and temporary refs never grant access.

Changes affect ai-agent-service application/domain/adapters, existing conversation/provider settings/UI boundaries where relevant, and their contracts. Owning domain services retain authority. Existing history/traces/receipts are reused.

## Out of scope
No unrequested implementation or paid trials from this proposal, no fine-tuning,
semantic phrase-matching fallback, direct model mutations, fabricated telemetry,
provider credential changes, automatic publication, hidden model escalation or new mandatory agent framework.

## Acceptance
The requirements in specs/assistant-skill-context/spec.md and every task below require implementation and evidence. Preserve existing failed-quality records, default-off rollout and immutable legacy execution. No checkbox is completed merely by creating a proposal.
