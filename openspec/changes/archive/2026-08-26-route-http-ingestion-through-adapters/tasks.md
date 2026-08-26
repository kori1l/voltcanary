# Tasks: Route HTTP Ingestion Through Adapters

## 1. HTTP Adapter Routing

- [x] 1.1 Add `IngressOrchestrator` for transport-independent adapter dispatch.
- [x] 1.2 Add `HttpTelemetryAdapter` for single realtime ingestion.
- [x] 1.3 Move `RestTelemetryMapper` responsibilities into the HTTP adapter layer or delete it after replacement.
- [x] 1.4 Route `POST /ingestion/readings` through controller -> orchestrator -> adapter -> pipeline.

## 2. Batch And Imports

- [x] 2.1 Route `POST /ingestion/readings/batch` through HTTP adapter batch handling.
- [x] 2.2 Route import endpoints through canonical import mode.
- [x] 2.3 Preserve per-item validation, idempotency, duplicate, and partial-success semantics.

## 3. Documentation And Verification

- [x] 3.1 Update `docs/api/ingestion-api.md` if request, response, or failure semantics change.
- [x] 3.2 Add or update HTTP ingestion tests around the adapter route.
- [x] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [x] 3.4 Run `npm run openspec:validate`.
