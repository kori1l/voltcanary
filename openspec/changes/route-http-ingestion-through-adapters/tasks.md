# Tasks: Route HTTP Ingestion Through Adapters

## 1. HTTP Adapter Routing

- [ ] 1.1 Add `IngressOrchestrator` for transport-independent adapter dispatch.
- [ ] 1.2 Add `HttpTelemetryAdapter` for single realtime ingestion.
- [ ] 1.3 Move `RestTelemetryMapper` responsibilities into the HTTP adapter layer or delete it after replacement.
- [ ] 1.4 Route `POST /ingestion/readings` through controller -> orchestrator -> adapter -> pipeline.

## 2. Batch And Imports

- [ ] 2.1 Route `POST /ingestion/readings/batch` through HTTP adapter batch handling.
- [ ] 2.2 Route import endpoints through canonical import mode.
- [ ] 2.3 Preserve per-item validation, idempotency, duplicate, and partial-success semantics.

## 3. Documentation And Verification

- [ ] 3.1 Update `docs/api/ingestion-api.md` if request, response, or failure semantics change.
- [ ] 3.2 Add or update HTTP ingestion tests around the adapter route.
- [ ] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [ ] 3.4 Run `npm run openspec:validate`.
