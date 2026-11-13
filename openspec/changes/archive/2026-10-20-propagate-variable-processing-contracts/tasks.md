## 1. Implementation and verification

- [x] 1.1 Define and compatibility-test secret-free processing bundle event and optional raw processingContext DTOs.
- [x] 1.2 Provision compacted processing topic and implement device transactional outbox with stable source key and generation.
- [x] 1.3 Implement authenticated cursor reconciliation including historical source/generation lookup and disable snapshots.
- [x] 1.4 Build complete ingestion replica projections with generation/digest checks and compatible source-runtime references.
- [x] 1.5 Build durable telemetry bundle history/projection with bootstrap, recovery and monotonic event handling.
- [x] 1.6 Extend configured-field input validation/retention and reject spoofed processing context or computed namespaces.
- [x] 1.7 Pin acceptance context and preserve input dedup identity through configuration edits and retries.
- [x] 1.8 Implement bounded durable parking/reconciliation/DLQ for telemetry events with missing pinned bundles.
- [x] 1.9 Test duplicate/out-of-order config, two replicas, restart, revocation, lag, custom-only inputs and legacy HTTP/MQTT/import/GPS.
- [x] 1.10 Document actual topic schemas/upgrade/rollback/reconciliation operations; run common-events/device/ingestion/telemetry checks and strict OpenSpec validation.

## Implementation evidence

See [current verification](../../../../docs/current/flows/device-variable-verification.md),
[current architecture](../../../../docs/current/architecture/device-variables.md) and
[operation commands](../../../../docs/current/operations/device-variables.md).
All ten tasks are implemented and verified for the declared first-version scope. This change remains active for review.

