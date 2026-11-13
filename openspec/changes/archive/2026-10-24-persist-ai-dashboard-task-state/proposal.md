# Change: Make dashboard working state durable independently of preparation

## Why
Names and unfinished changes must survive a rejected composition or provider call.
Today `TaskConversationMemory`, `PlanClarifications` and `_clarification.input`
already preserve many values, but state is map-based and saved with a resulting
plan. Recovery and pending-work semantics must not depend on successful planning
or on reconstructing facts from assistant wording.

## What Changes
- Introduce a versioned dashboard task/turn state independent of immutable execution
  reviews, with provenance for explicit facts and an ordered pending-change ledger.
- Persist accepted user turns before remote planning; preserve parsed explicit facts
  before technical preparation and failed turns without replacing a valid review.
- Add revision/idempotency controls for duplicate requests, conflicting concurrent
  continuations, restart recovery and explicit branches from restored reviews.
- Extend recovery responses additively; preserve legacy plan IDs, GET/execute
  contracts, receipts, authorization, expiry and exact review history.

## Dependencies
Requires `establish-ai-dashboard-conversation-evals` baseline/harness and current
durable reviewed tasks. Supersedes the unfinished name/pending-state portion of
section11 in `redesign-ai-assistant-dashboard-preview`, rather than adding a
second independent implementation. Existing helpers are migration inputs.

## Ownership and impact
ai-agent-service owns state invariants, turn persistence, scoped reads and migrations.
The ordinary frontend assistant consumes safe summaries and remembers task identity;
it does not own names or infer facts from question text. Dashboard/device services
retain their resource authority. No new external state service, Kafka topic,
multi-agent framework or stored JWT is introduced.

## Out of scope
No generic agent memory platform, transcript-based authorization, automatic domain
mutation or change to confirmed execution semantics. Device-creation state is not
migrated to the new dashboard-specific protocol in this change.

## Acceptance
Explicit names, original request and pending changes survive rejection, provider
failure, process restart and reload. Concurrent turns cannot overwrite accepted
facts, retrying a request is idempotent, and restored branches start from the exact
selected review. Known facts are reused without another name question. Foreign
accounts cannot discover task or branch identities.
