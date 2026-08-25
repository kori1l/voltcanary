# Tasks: Verify Ingestion Horizontal Scale

## 1. Smoke Environment

- [ ] 1.1 Add a local or CI smoke path that starts at least two ingestion-service replicas with shared Kafka/Redpanda and Redis.
- [ ] 1.2 Seed or publish source runtime config required by the smoke.
- [ ] 1.3 Add a concise smoke summary artifact and keep it out of committed generated artifacts by default.

## 2. Scale Assertions

- [ ] 2.1 Verify HTTP push requests can hit any ingestion replica and publish canonical telemetry.
- [ ] 2.2 Verify duplicate telemetry sent to different replicas is accepted once.
- [ ] 2.3 Verify rate limits are shared across replicas.
- [ ] 2.4 Verify one active connector source has exactly one owner.
- [ ] 2.5 Verify connector ownership moves after owner failure.
- [ ] 2.6 Verify `telemetry.raw.v1` uses the documented deterministic Kafka key.

## 3. Documentation And Verification

- [ ] 3.1 Document horizontal ingestion deployment and troubleshooting steps.
- [ ] 3.2 Run the new ingestion horizontal scale smoke.
- [ ] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [ ] 3.4 Run `npm run openspec:validate`.
