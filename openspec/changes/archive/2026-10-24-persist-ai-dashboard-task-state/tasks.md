## 1. Domain and persistence
- [x] 1.1 Map existing taskMemory/context_json/name/history paths and define typed state, fact provenance, turn transitions and pending-change dispositions.
- [x] 1.2 Add persistence port/JDBC adapter and additive Flyway migration with scoped indexes, optimistic revision, request deduplication, expiry and retention.
- [x] 1.3 Persist accepted turns before provider calls and confirmed fact patches before preparation; record failed/interrupted turns without losing the last valid review.
- [x] 1.4 Implement bounded legacy context adaptation and explicit branch creation from authorized stored reviews; retain execution immutability.

## 2. Application and interface
- [x] 2.1 Add request/revision metadata and safe task summaries to planning/clarification responses; add owner-scoped read-only initial-task recovery.
- [x] 2.2 Continue by stored task kind, reuse known facts and preserve pending work until outcome verification or explicit supersession.
- [x] 2.3 Wire existing frontend API/conversation/recovery/history to stable retry IDs and server summaries; preserve previous preview and invalidate stale confirmation.
- [x] 2.4 Align API, reviewed-task operations and verification docs, including legacy metadata limits and failure statuses.

## 3. Verification
- [x] 3.1 Test unquoted/quoted names, accepted defaults, conflicting facts, failed preparation and provider failure without repeated known-name questions.
- [x] 3.2 Test PostgreSQL restart, duplicate/lost responses, concurrent turns, branch isolation, expired/foreign resources and interrupted-turn recovery.
- [x] 3.3 Run browser reload/undo/branch continuation and repeated real-model state cases from the shared corpus; retain failures honestly.
- [x] 3.4 Run affected Java/frontend checks, production build, structure and strict OpenSpec validation; record migration/rollback evidence.
