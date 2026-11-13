## 1. Implementation and verification

- [x] 1.1 Define actor/account resolution, scope and typed plan/version/status contracts; inventory existing callers and compatible migration.
- [x] 1.2 Implement server-side context catalogues, task-local refs and recursive secret/size validation using existing authorized domain APIs.
- [x] 1.3 Add durable plan/action/ref/audit schema, scoped repository queries, expiry/retention and authenticated status recovery.
- [x] 1.4 Implement immutable reviewed versions, atomic execution claims, stable idempotency keys, unknown/partial outcomes and reconciliation port without storing user JWTs.
- [x] 1.5 Extract focused intent/compiler/domain-executor components and bound model repair/fallback behavior while preserving explicit legacy tools.
- [x] 1.6 Update assistant plan/status/clarification UI and API helpers; invalidate stale confirmation and isolate session changes without raw JSON requirements.
- [x] 1.7 Verify two-account isolation, injected context, changed rights, parallel replicas, restart, expiry, replay and lost-response behavior in backend/PostgreSQL and frontend tests.
- [x] 1.8 Run focused AI Maven tests, frontend typecheck/test/build and browser acceptance; update current AI API/security/runbook, model-run linkage and program indexes; validate OpenSpec/structure.

Evidence: [AI creation verification](../../../../docs/current/flows/ai-creation-verification.md).


