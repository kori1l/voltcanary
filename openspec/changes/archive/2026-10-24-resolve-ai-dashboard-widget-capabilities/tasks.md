## 1. Domain and read contract
- [x] 1.1 Inventory current metric/source-field/domain-evidence preparation and graph support; document implemented behavior versus remaining acceptance gaps.
- [x] 1.2 Define typed compatibility choices and diagnostics using the existing descriptor, actual units/types, source identity and ordinary semantic requirements.
- [x] 1.3 Add application resolver/ports/adapters and bounded authenticated read-only capabilities API; retain existing catalogue endpoints.
- [x] 1.4 Distinguish ambiguity, incompatibility, absent samples, stale evidence, denied access and upstream failure without guessed mappings or readings.

## 2. Integration
- [x] 2.1 Renew scoped data refs in DashboardPlanningContext and persisted selections; consolidate DashboardVehicleFields normalization against actual choices.
- [x] 2.2 Reuse shared data selection/manifest metadata in the editor and retain General/Appearance behavior; avoid a second compatibility catalogue.
- [x] 2.3 Verify numeric/boolean source history and text list behavior, filling only proven renderer/configuration gaps with explicit tests.
- [x] 2.4 Document the additive API, limits, compatibility and source-field versus registered-metric semantics in current contracts at implementation time.

## 3. Verification
- [x] 3.1 Test matrix agreement, same-label sources, type drift, denied refs, narrowing limits, unsupported views, units and energy semantic prerequisites.
- [x] 3.2 Test false/zero/missing/stale/gapped observations and preservation of current-value versus history roles.
- [x] 3.3 Run repeated real-model field-selection cases and inspect ordinary editor/shared preview in Docker; record HSL availability separately.
- [x] 3.4 Run affected backend/frontend tests, typecheck/build, structure and strict OpenSpec validation with sanitized evidence.
