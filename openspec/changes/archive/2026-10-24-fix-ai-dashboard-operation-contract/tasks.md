## 1. Stage4 corrections
- [x] 1.1 Reproduce contract gaps with focused regression tests.
- [x] 1.2 Implement exact keep_review, local rename references, replacement scope and view-aware reuse.
- [x] 1.3 Tighten schema/prompt constraints and document semantics/compatibility.
- [x] 1.4 Run Docker Java checks and repeated affected real-model scenarios; preserve earlier scores.
- [x] 1.5 Record evidence and limitations; pass structure and strict OpenSpec validation.

## Evidence

Initial compiler/history regressions:16 tests,2 failures,2 errors before fixes.
Live keep regression exposed pending-state semantics; fixed without marking goals
applied/superseded. Legacy absent-view regression was red, then full AI131 passed.
Dashboard93/shared10 passed; final context/projection/credential31 checks passed.
Frozen corrective sample5/8 dialogues,13/16 turns vs0/8,7/16; targeted4/6,28/32.
Later context replacement supplement0/2,2/4 reaches model without HTTP400, with
remaining interpretation/preservation failures. Revision2 binding predicate gap
is independently audited and handed to stage5. Raw snapshots/scores retained.
See docs/current/flows/ai-creation-verification.md and ignored corrective report.
Default remains off; no stage5/6 or final model-quality acceptance claimed.

Rollback: flagfalse, exact stored definition/digest readable; synthetic fixtures cleaned.
Structure and strict OpenSpec validation passed before archive.
