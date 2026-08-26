# Tasks: Define Ingestion Adapter Contracts

## 1. Contracts

- [x] 1.1 Add adapter type, protocol type, transport type, capabilities, and manifest models.
- [x] 1.2 Add capability-specific adapter interfaces for inbound, polling, and subscription flows.
- [x] 1.3 Add adapter registry and resolver/dispatcher contracts.
- [x] 1.4 Reshape `IncomingTelemetryMessage` into the canonical ingress contract.
- [x] 1.5 Add `TelemetryOrigin`, `IngestionContext`, and `SourceRuntimeConfig` models.

## 2. Boundaries

- [x] 2.1 Move common validation, normalization, idempotency, rate limit, and event creation behind protocol-neutral packages.
- [x] 2.2 Keep concrete adapter packages outside the common ingestion core dependency path.
- [x] 2.3 Add ArchUnit or equivalent structure tests for adapter/core dependency rules.

## 3. Documentation And Verification

- [x] 3.1 Update ingestion architecture/API docs or ADR notes that describe the canonical adapter boundary.
- [x] 3.2 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [x] 3.3 Run `npm run openspec:validate`.
