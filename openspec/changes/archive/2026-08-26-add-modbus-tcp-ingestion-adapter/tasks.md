# Tasks: Add Modbus TCP Ingestion Adapter

## 1. Adapter

- [x] 1.1 Select and add a Modbus TCP client dependency consistent with backend dependency policy.
- [x] 1.2 Add generic Modbus TCP adapter manifest and polling capability.
- [x] 1.3 Add versioned Modbus adapter configuration and register mapping validation.
- [x] 1.4 Map Modbus register values into canonical telemetry metrics without changing common pipeline logic.

## 2. Runtime Integration

- [x] 2.1 Schedule Modbus polling only through connector runtime ownership.
- [x] 2.2 Report per-source health for timeout, connection failure, invalid mapping, and lease loss.
- [x] 2.3 Add retry/backoff behavior for Modbus operation and lifecycle failures.

## 3. Documentation And Verification

- [x] 3.1 Document Modbus source config, mapping fields, and operational failure modes.
- [x] 3.2 Add adapter, connector runtime, and architecture boundary tests.
- [x] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [x] 3.4 Run `npm run openspec:validate`.
