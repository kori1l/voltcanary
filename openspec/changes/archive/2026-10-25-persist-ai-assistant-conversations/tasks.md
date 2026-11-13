## 1. Contracts and storage
- [x] 1.1 Define conversation/message/outcome DTOs, pagination, idempotency, revision conflicts, retention and migration from existing recovery pointers; align API docs.
- [x] 1.2 Implement owner-scoped durable conversation/message storage and additive APIs, secret redaction and retention cleanup.
- [x] 1.3 Link planning, failures and execution receipts to submitted messages; recover interrupted requests without replaying side effects or rewriting earlier replies.

## 2. Shared conversation UI
- [x] 2.1 Load and continue server history across page/drawer/floating modes, reload and browser reopening; preserve owner-scoped unsent drafts with truthful sync state.
- [x] 2.2 Handle concurrent tabs, lost responses, expired resources and identity changes; keep unrelated conversations independent.

## 3. Acceptance
- [x] 3.1 Test persistence/restart, pagination, request deduplication, revision races, redaction, retention and cross-account denial at backend and frontend boundaries.
- [x] 3.2 Run Docker/browser bus creation and recovery acceptance with unchanged execution count; document evidence and run affected checks, build, structure and strict OpenSpec validation.
