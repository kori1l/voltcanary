# Tasks: Add HTTP Polling Ingestion Adapter

## 1. Adapter

- [ ] 1.1 Add generic HTTP polling adapter manifest and polling capability.
- [ ] 1.2 Add versioned HTTP polling adapter configuration model and validation.
- [ ] 1.3 Implement response timestamp and metric mapping into canonical telemetry.
- [ ] 1.4 Resolve HTTP polling credentials through credential references.

## 2. Runtime Integration

- [ ] 2.1 Register HTTP polling sources with connector runtime scheduling.
- [ ] 2.2 Ensure adapter polling starts only after source lease ownership.
- [ ] 2.3 Report per-source health and retry state for polling failures.

## 3. Documentation And Verification

- [ ] 3.1 Document HTTP polling source config and expected failure behavior.
- [ ] 3.2 Add adapter and runtime integration tests with mock HTTP responses.
- [ ] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [ ] 3.4 Run `npm run openspec:validate`.
