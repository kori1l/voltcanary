# Implementation tasks

## 1. Baseline and design

- [x] 1.1 Read the audit coverage (F26, F27, F28, F29, F30, F31, F32, F33, F34), existing governing specs and applicable surface skills; inspect affected typed API contracts.
- [x] 1.2 Capture current populated and failure states, identify code-only audit fixtures and review the target desktop/mobile layout against shared prototypes.

## 2. Implementation

- [x] 2.1 Extract focused ML tabs, import mapping, labels, training, registry and policy modules.
- [x] 2.2 Implement pipeline remediation, compatible named selectors and responsive Training/Policies.
- [x] 2.3 Implement bounded visual CSV/JSONL mapping and readable detail/lineage/lifecycle views.
- [x] 2.4 Preserve preview stale/out-of-order guards and horizon compatibility with payload tests.
- [x] 2.5 Verify populated dataset/model/policy/label fixtures and run ML production smoke when runtime/lifecycle boundaries change; update ML guidance.
- [x] 2.6 Implement and integrate requirement: Actionable ML pipeline and scope.
- [x] 2.7 Implement and integrate requirement: Preview-bound dataset preparation.
- [x] 2.8 Implement and integrate requirement: Readable dataset snapshot and import workflows.
- [x] 2.9 Implement and integrate requirement: Auditable labels and generation.
- [x] 2.10 Implement and integrate requirement: Guided contained training.
- [x] 2.11 Implement and integrate requirement: Reviewed model and deployment decisions.
- [x] 2.12 Implement and integrate requirement: Evidence-backed ML monitoring.

## 3. Verification and documentation

- [x] 3.1 Add meaningful workflow/payload/state-transition tests and verify existing domain invariants and authorization boundaries.
- [x] 3.2 Verify all covered forms in browser with populated fixtures, keyboard/focus, normal/loading/empty/error/stale states, long content and responsive widths including text zoom.
- [x] 3.3 Run frontend typecheck, test and build; run OpenSpec validation and structure check; run required ML production smoke when lifecycle/runtime boundaries change.
- [x] 3.4 Update current workflow/API guidance for implemented behavior and audit traceability; record validation evidence without committing generated artifacts or secrets.
- [x] 3.5 Archive only after all implementation tasks and required checks pass; do not close tasks merely because this proposal validates.


## Verification record

Implementation and validation evidence: [Console interface workflows](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06). Browser verification uses local fixtures and does not establish live delivery or ML-runtime claims. Runtime boundaries were unchanged; the conditional ML production smoke did not apply.

Archive gate satisfied: all implementation tasks and required checks have passed. Archiving was requested by the user on 2026-10-07.
