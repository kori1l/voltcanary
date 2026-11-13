# Implementation and Verification

**Superseded queue:** tasks below are historical proposed scope, not eligible
implementation targets. Requirements are decomposed under G1–G8 in the
[configurable operations program](../README.md#configurable-operations-program).
Keep these tasks unchecked; implementation remains stopped.

- [ ] 1.1 Specify operational ingestion schema, stable key/ACK semantics, quality fields and EventEnvelope topics.
- [ ] 1.2 Implement bounded durable connector spool and allowlisted event mapping while preserving legacy latest mode.
- [ ] 1.3 Implement authenticated batched ingress, durable acceptance/outbox and replay-safe ACK.
- [ ] 1.4 Persist telemetry operational inbox/events/coverage and publish accepted observations transactionally.
- [ ] 1.5 Add authorized evidence reads, cutoff pagination, expiry and source-revocation handling.
- [ ] 1.6 Test crashes at each persistence/ACK boundary, cross-session duplicates, equal timestamps, clock skew, outage and overflow.
- [ ] 1.7 Measure the proposed 20-vehicle burst/spool envelope and retention footprint; document actual limits.
- [ ] 1.8 Run connector tests and focused ingestion/telemetry tests; align implemented docs and validate structure/OpenSpec.

