## 1. Runtime Policy Resolution

- [ ] 1.1 Make analytics recalculation resolve and pass device type.
- [ ] 1.2 Validate model compatibility by device type and feature schema.
- [ ] 1.3 Add tests for device, device-type, account, system, and fallback resolution in live recalculation.
- [ ] 1.4 Store resolved device type and policy source in prediction traceability.

## 2. Canary And Shadow

- [ ] 2.1 Implement deterministic canary selection.
- [ ] 2.2 Store canary inclusion and canary percent in prediction traceability.
- [ ] 2.3 Separate shadow, canary, active, and fallback records.
- [ ] 2.4 Add tests proving shadow does not affect active risk.
- [ ] 2.5 Add tests proving canary includes only deterministic selected devices.

## 3. Production Registry Hygiene

- [ ] 3.1 Gate demo model seeding behind local/demo configuration.
- [ ] 3.2 Ensure production registry distinguishes system heuristic from trained models.
- [ ] 3.3 Hide or label demo models in frontend.
- [ ] 3.4 Add tests for production model list behavior.

## 4. Monitoring

- [ ] 4.1 Add model-specific inference latency and error metrics.
- [ ] 4.2 Add fallback, feature mismatch, and missing feature metrics.
- [ ] 4.3 Add shadow-vs-active and canary comparison metrics.
- [ ] 4.4 Add drift metrics by feature schema/model.
- [ ] 4.5 Add outcome-based false-alert and lead-time metrics when labels exist.

## 5. Frontend And Docs

- [ ] 5.1 Add deployment comparison UI for active, shadow, canary, and fallback paths.
- [ ] 5.2 Add monitoring cards for latency, fallback, drift, and quality.
- [ ] 5.3 Update ML operations runbooks.
- [ ] 5.4 Update API docs for traceability fields.

## 6. Verification

- [ ] 6.1 Run analytics-service policy and traceability tests.
- [ ] 6.2 Run ML-service inference status tests.
- [ ] 6.3 Run frontend monitoring tests.
- [ ] 6.4 Run `npm run openspec:validate`.
