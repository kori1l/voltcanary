## 1. Implementation and verification

- [x] 1.1 Define typed CREATE_DEVICE intent, limits and authorized catalogue/ref resolution with explicit name/type/location clarification rules.
- [x] 1.2 Implement deterministic prepare/compiler and remove invented manufacturer/model/simulator defaults from the new real-device workflow.
- [x] 1.3 Add optional owning POST /devices idempotency contract, transactional receipts, payload conflict/replay rules and migration preserving ordinary callers.
- [x] 1.4 Integrate current-JWT execution with durable task state and existing account/location validation; handle denied, duplicate, unknown and restart outcomes.
- [x] 1.5 Add focused readable review/clarification/result components and normal source-setup handoff without credentials in model context.
- [x] 1.6 Add two-account, parallel/lost-response/rollback/revocation tests and frontend stale-response/cancel/retry tests.
- [x] 1.7 Commit the bounded Russian intent fixture and run deterministic checks plus recorded evaluation against the configured small model using declared acceptance thresholds.
- [x] 1.8 Run focused Maven suites, frontend typecheck/test/build, real browser/stack create-and-cleanup acceptance; update current API/AI verification docs and validate OpenSpec/structure.

Evidence: [verified implementation](../../../../docs/current/flows/ai-creation-verification.md).


