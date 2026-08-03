## 1. Runtime Policy Resolution

- [x] 1.1 Make analytics recalculation resolve and pass device type.
- [x] 1.2 Validate model compatibility by device type and feature schema.
- [x] 1.3 Add tests for device, device-type, account, system, and fallback resolution in live recalculation.
- [x] 1.4 Store resolved device type and policy source in prediction traceability.

## 2. Canary And Shadow

- [x] 2.1 Implement deterministic canary selection.
- [x] 2.2 Store canary inclusion and canary percent in prediction traceability.
- [x] 2.3 Separate shadow, canary, active, and fallback records.
- [x] 2.4 Add tests proving shadow does not affect active risk.
- [x] 2.5 Add tests proving canary includes only deterministic selected devices.

## 3. Production Registry Hygiene

- [x] 3.1 Gate demo model seeding behind local/demo configuration.
- [x] 3.2 Ensure production registry distinguishes system heuristic from trained models.
- [x] 3.3 Hide or label demo models in frontend.
- [x] 3.4 Add tests for production model list behavior.

## 4. Monitoring

- [x] 4.1 Add model-specific inference latency and error metrics.
- [x] 4.2 Add fallback, feature mismatch, and missing feature metrics.
- [x] 4.3 Add shadow-vs-active and canary comparison metrics.
- [x] 4.4 Add drift metrics by feature schema/model.
- [x] 4.5 Add outcome-based false-alert and lead-time metrics when labels exist.

## 5. Frontend And Docs

- [x] 5.1 Add deployment comparison UI for active, shadow, canary, and fallback paths.
- [x] 5.2 Add monitoring cards for latency, fallback, drift, and quality.
- [x] 5.3 Update ML operations runbooks.
- [x] 5.4 Update API docs for traceability fields.

## 6. Verification

- [x] 6.1 Run analytics-service policy and traceability tests.
- [x] 6.2 Run ML-service inference status tests.
- [x] 6.3 Run frontend monitoring tests.
- [x] 6.4 Run `npm run openspec:validate`.
