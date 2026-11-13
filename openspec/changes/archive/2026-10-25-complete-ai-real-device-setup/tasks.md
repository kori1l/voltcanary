## 1. Device workflow
- [x] 1.1 Preserve explicit name/type and original request through device clarifications; cover reported dialogue.
- [x] 1.2 Add dedicated keyed device setup execution with an independent integration flag and truthful states.
## 2. HSL adapter
- [x] 2.1 Add owner-authorized, bounded, replayable provisioning and status with atomic binding persistence/live reload.
- [x] 2.2 Verify authorization, conflicts, failures, replay, field policy and preservation of existing bindings.
## 3. Acceptance
- [x] 3.1 Update result UI and operational/API documentation; preserve ordinary dashboard handoff.
- [x] 3.2 Run Docker tests and smoke supported setup, waiting/recovery and dashboard compatibility; record evidence.

Evidence: 130 AI unit/service tests and 9 PostgreSQL integration tests passed in
Docker (the latter rerun with a Docker socket after the ordinary app container
could not start Testcontainers). 48 assistant UI tests, TypeScript and frontend
production build passed; 16 connector tests passed. Configured gpt-5-nano passed
five non-mutating model checks including the supplied HSL prompt, plain-name
clarification and ordinary dashboard handoff. Full live execution on temporary
bus 0012-02618 returned WAITING_FOR_DATA, subsequently TELEMETRY_READY through
the owner API; repeated execution reused the device and exactly one source.
The fixture was decommissioned and its private binding removed; all 17 original
bindings were preserved. An initial live run exposed chunked HTTP rejection
before mutation; fixed and covered by a real HTTP regression test before rerun.
Ignored evidence: artifacts/device-setup-model-smoke.json and
artifacts/device-setup-execution-smoke.json. This focused smoke is not an
exhaustive model-quality acceptance for every integration or dashboard scenario.
