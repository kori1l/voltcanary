# Design

## Source of truth and boundaries
ai-agent-service owns conversation metadata and immutable ordered message/event
records. Existing reviewed plans, dashboard branches and execution receipts remain
their respective authorities; conversations reference them rather than cloning their
mutable status. Separate focused persistence, API and UI adapters from planning.

Proposed additive routes: POST/GET `/ai-agent/conversations`, GET/PATCH
`/ai-agent/conversations/{id}`, GET `/ai-agent/conversations/{id}/messages` and
POST `/ai-agent/conversations/{id}/messages`. Contract exact DTOs/statuses before
implementation: authenticated owner/account scope, cursor pagination, conversationId,
messageId, requestId, revision, role, display text, timestamps, task/turn/review/action
links and structured outcome. Never accept ownership from request fields.

## Writes and recovery
Persist user input before model invocation; record pending state, assistant response
and execution outcome durably, including technical failure or cancellation. Stable
requestId deduplicates delivery; revision conflicts return current state without
duplicating messages. Planning/execution are not a single long database transaction.
On restart reconcile an interrupted attempt through existing receipts/leases; report
unknown/pending truthfully rather than replaying side effects automatically.

The same conversation is used in drawer, floating and full-page modes. Loading history
does not call the provider, prepare a review or confirm execution. Persist submitted
messages on the server; keep unsent drafts in an owner-scoped local draft cache with
clear unsaved/sync failure state. Server history is authoritative across clients.
Changing identity clears visible state and ignores late responses from the old owner.

## Lifetime and compatibility
Keep completed and failed conversations visible independently of review expiry;
expired/deleted linked resources are shown as unavailable and cannot execute. Define
and document finite configurable retention before rollout, expose expiration metadata,
and test purge of associated records without breaking shared audit retention.
Redact credentials before persistence; do not store JWTs, source tokens or provider
keys in messages, links, drafts or diagnostics. Inform users where redaction occurred.
Existing last-plan pointers can offer a labelled recovered plan, not invented messages.

## Verification
Test reload while pending, after success/failure, service restart, two tabs, duplicate
request after lost response, draft restore, expired review, logout/account switch and
page/drawer transitions. Use isolated Docker fixtures and real browser acceptance;
compare message IDs/order/count and domain execution receipts before/after recovery.
