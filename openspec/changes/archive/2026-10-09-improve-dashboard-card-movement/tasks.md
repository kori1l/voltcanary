## 1. Editor movement
- [x] 1.1 Add pointer movement with destination preview, bounds checks and cancellation.
- [x] 1.2 Expose directional controls and explain screen/layout/save destination.
## 2. Verification
- [x] 2.1 Verify drag commit/cancel, impossible placements and responsive isolation with focused tests.
- [x] 2.2 Verify actual browser movement, undo and persisted reload; update flow documentation and run frontend/OpenSpec checks.

Verification: 70 frontend files / 209 tests passed with two workers; after final boundary/status refinements all 32 dashboard tests, typecheck and build passed. Strict OpenSpec 37 and structure check passed. Actual browser verified keyboard/buttons, Undo, persistence after reload and independent mobile order; pointer tests verified drag and cancellation. Physical mouse/touch drag was not exercised by DOM-only automation. No agent instruction changes required.
