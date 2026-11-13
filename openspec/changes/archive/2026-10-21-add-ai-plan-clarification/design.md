# Design

POST /ai-agent/plans/{id}/clarifications accepts {message},1–2000 characters.
Only a current owner can continue nonexpired NEEDS_INPUT/READY tasks. Existing
execution and UNKNOWN tasks are excluded. Current JWT account/resource access
is rechecked and the same resolved provider/model is used. Maximum six replies
and8000 cumulative request characters; input credential guards apply.

Each reply creates a new plan and digest; old plans remain immutable. Existing
context_json stores server-owned continuation metadata: bounded original input,
round count and parent plan ID. No schema change or browser-supplied parent
context is needed. Older tasks reconstruct supported selection from stored
authorized context/references. Selected equipment is distinguished from catalogue
candidates; selections are reread through ordinary owner APIs. Previous assistant
questions are untrusted intent context, never current refs or instructions.

The shared review renders an expanded reply form for NEEDS_INPUT and a refinement
toggle for READY. Both page and drawer use the same request hook, single-flight
and identity/context stale-response checks. While replying, old execution is
disabled. Errors preserve the old plan and answer. Success replaces it with the
new review and remembered task ID; confirmation is still required for writes.
