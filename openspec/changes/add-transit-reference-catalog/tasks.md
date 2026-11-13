# Implementation and Verification

**Superseded queue:** tasks below are historical proposed scope, not eligible
implementation targets. Requirements are decomposed under G1–G8 in the
[configurable operations program](../README.md#configurable-operations-program).
Keep these tasks unchecked; implementation remains stopped.

- [ ] 1.1 Specify DTOs, ownership, import receipts, gateway routes, pagination and validation limits.
- [ ] 1.2 Implement GTFS import adapter and optional keyed Digitransit adapter with safe archive/network bounds.
- [ ] 1.3 Persist immutable snapshots, effective dates and source attribution; implement refresh/archive guards.
- [ ] 1.4 Implement trip/occurrence matching including direction, service day, DST and repeated stops.
- [ ] 1.5 Implement approved required-service policies and fresh cancellation/detour evidence.
- [ ] 1.6 Test malformed imports, unavailable/stale providers, ambiguous matching and two-account isolation.
- [ ] 1.7 Update current API/operations docs only after implementation; run focused device tests, structure and OpenSpec validation.

