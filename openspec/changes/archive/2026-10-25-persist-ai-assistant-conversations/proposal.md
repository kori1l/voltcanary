# Change: Persist complete assistant conversations

## Why
The assistant keeps its transcript and conversation list in React state. Reviewed
plans and dashboard tasks are durable, but do not reconstruct a complete chat.
Reloading loses the user's working conversation and makes failures hard to reproduce.

## What Changes
- Store owner-scoped conversations, ordered messages, task/review/execution links
  and the active conversation in ai-agent-service; share them across assistant modes.
- Restore messages and pending/failed/completed outcomes after reload or restart
  without calling the model or executing a command again.
- Preserve drafts separately, support paginated history, and handle concurrent tabs
  and lost responses with stable request IDs and revision checks.
- Keep execution summaries as events/messages rather than overwriting prior replies.

## Dependencies and priority
First corrective change, before diagnostic tracing, context handoff and existing
dashboard reliability stages5/6. Existing reviewed execution and task state are reused.
This proposal authorizes planning only; implementation tasks remain unchecked.

## Acceptance
The bus creation conversation survives reload, browser reopening and service restart;
its messages, selected conversation and execution outcome are recoverable. Resuming
does not create another bus. A different account cannot read or continue it.

## Scope
Backend persistence/API, shared assistant frontend and verification docs. No model
training, no new dashboard compiler, and no promise to recover transcripts already
lost from browser memory. Existing reviews may be linked as recovered records, never
presented as a reconstructed verbatim chat.
