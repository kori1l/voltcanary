# Tasks: Add OPC UA Ingestion Adapter

## 1. Adapter

- [ ] 1.1 Select and add an OPC UA client dependency consistent with backend dependency policy.
- [ ] 1.2 Add generic OPC UA adapter manifest and subscription capability.
- [ ] 1.3 Add versioned OPC UA adapter configuration and node mapping validation.
- [ ] 1.4 Map OPC UA node changes into canonical telemetry metrics without changing common pipeline logic.

## 2. Runtime Integration

- [ ] 2.1 Start OPC UA sessions only after connector runtime ownership is acquired.
- [ ] 2.2 Stop sessions immediately on lease loss, source disable, or config replacement.
- [ ] 2.3 Report per-source health for session loss, endpoint failure, auth failure, invalid mapping, and reconnect state.

## 3. Documentation And Verification

- [ ] 3.1 Document OPC UA source config, credential/certificate references, and operational failure modes.
- [ ] 3.2 Add adapter, connector runtime, and architecture boundary tests.
- [ ] 3.3 Run `cd backend; mvn -pl services/ingestion-service -am test`.
- [ ] 3.4 Run `npm run openspec:validate`.
