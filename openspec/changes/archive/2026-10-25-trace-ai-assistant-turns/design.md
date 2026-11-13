# Design

Append bounded versioned events linked by conversationId, messageId, turnId,
requestId, attemptId and optional reviewId/actionId/modelRunId. Record stage, status,
start/end or duration, diagnostic code, safe field paths, snapshot/version references,
selected resource IDs, candidate operations and validation outcomes. Each provider
invocation, including failed/uncertain calls, has an attempt record. Do not equate
HTTP/model success with an accepted review or successful domain command.

Capture observable artifacts only: redacted requests, bounded structured model
outputs, capability summaries/digests, failed checks and tool results. Do not request,
persist or expose chain-of-thought. Redact before storage, not just on export; exclude
authorization headers, tokens, passwords, provider secrets and raw upstream bodies.
Store exact safe rejected parameter names to diagnose sanitizer failures. Mark
truncation explicitly and link large safe artifacts under bounded retention.

Prefer a focused recorder port with persistence adapters. Define handling of recorder
failure before rollout: persist required start/outcome checkpoints before further
side effects, use existing execution receipts for recovery, never repeat an action
because a diagnostic write failed. Optional detailed payload loss is explicit.
Use append-only sequence and reconciliation for crash-interrupted stages. Avoid
logging entire user/context objects to generic application logs.

Proposed read APIs: GET `/ai-agent/conversations/{id}/turns/{turnId}/trace` with cursor
and GET the same route plus `/export`. Enforce ordinary owner/account authorization;
no public endpoint or new global diagnostic bypass. UI exposes a compact timeline and
expandable technical details, with no secrets or raw internal exceptions in replies.
Set explicit configurable retention/byte limits and purge semantics aligned with
conversation retention; tests assert bounded volume and retained metadata on truncation.

Stage6 writes its later typed diagnostics/attempt-budget events through this recorder,
and stage5 contributes verification events. Neither adds a parallel trace store.
