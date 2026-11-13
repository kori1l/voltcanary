# Implementation and Verification

**Superseded queue:** tasks below are historical proposed scope, not eligible
implementation targets. Requirements are decomposed under G1–G8 in the
[configurable operations program](../README.md#configurable-operations-program).
Keep these tasks unchecked; implementation remains stopped.

- [ ] 1.1 Freeze typed rule, visit state, quality, event/time, outcome/correction and activation receipt schemas.
- [ ] 1.2 Implement focused rule compiler/CRUD/activation receipts, analytics rule/zone/snapshot projections and durable readiness ACK/start-checkpoint reconciliation for the same activation epoch.
- [ ] 1.3 Implement pure geometry/visit/service predicates and evidence-quality checks with pinned versions.
- [ ] 1.4 Persist partitioned visit state, durable timers, inbox/outbox and restart/replay behavior.
- [ ] 1.5 Integrate alert deduplication, corrections, maintenance/disable/revocation and existing notification ports.
- [ ] 1.6 Expose authorized dry-run and bounded evidence/visit reads; dry-run has no live notifications.
- [ ] 1.7 Prove normal pass, miss, no-stop, never-enter, jitter, overlapping zones, missing doors, late corrections, repeats and outages.
- [ ] 1.8 Run focused analytics/alert/common contract and PostgreSQL restart tests; update implemented docs and repository validation.

