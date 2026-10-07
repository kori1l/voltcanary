# Implementation tasks

## 1. Baseline and design

- [x] 1.1 Read the audit coverage (UI-02, UI-03, O07, O08, F06), existing governing specs and applicable surface skills; inspect affected typed API contracts.
- [x] 1.2 Capture current populated and failure states, identify code-only audit fixtures and review the target desktop/mobile layout against shared prototypes.

## 2. Implementation

- [x] 2.1 Extract typed section state/view models and partial-success dashboard loading.
- [x] 2.2 Replace fabricated empty/healthy/zero states across devices, telemetry, alerts and ML monitoring.
- [x] 2.3 Add failure, stale, empty and no-evidence fixtures plus meaningful state-transition tests.
- [x] 2.4 Implement and integrate requirement: Explicit independently loaded states.
- [x] 2.5 Implement and integrate requirement: Unknown is not healthy or zero.
- [x] 2.6 Implement and integrate requirement: Freshness and retry preserve operator context.
- [x] 2.7 Implement and integrate requirement: Useful fleet dashboard.

## 3. Verification and documentation

- [x] 3.1 Add meaningful workflow/payload/state-transition tests and verify existing domain invariants and authorization boundaries.
- [x] 3.2 Verify all covered forms in browser with populated fixtures, keyboard/focus, normal/loading/empty/error/stale states, long content and responsive widths including text zoom.
- [x] 3.3 Run frontend typecheck, test and build; run OpenSpec validation and structure check.
- [x] 3.4 Update current workflow/API guidance for implemented behavior and audit traceability; record validation evidence without committing generated artifacts or secrets.
- [x] 3.5 Archive only after all implementation tasks and required checks pass; do not close tasks merely because this proposal validates.


## Verification record

Implementation and validation evidence: [Console interface workflows](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06). Browser verification uses local fixtures and does not establish live delivery or ML-runtime claims. Runtime boundaries were unchanged; the conditional ML production smoke did not apply.

Archive gate satisfied: all implementation tasks and required checks have passed. Archiving was requested by the user on 2026-10-07.
