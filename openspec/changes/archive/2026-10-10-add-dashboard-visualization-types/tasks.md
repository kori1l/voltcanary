## 1. Implementation
- [x] 1.1 Map supported evidence shapes to visualization compatibility; decide renderer/library approach and validate any new dependency.
- [x] 1.2 Add version-compatible presentation/manifest contracts and server definition validation where required; preserve legacy rendering.
- [x] 1.3 Implement number, line, area and vertical/horizontal/stacked bars for compatible evidence.
- [x] 1.4 Implement pie and donut with exact authorized category totals and missing/negative/zero/partial safeguards.
- [x] 1.5 Add visual palette previews and the Presentation picker; preserve bindings, time, grid, undo, drafts, copies and publication.
- [x] 1.6 Implement responsive sizing and keyboard-accessible chart/category inspection with optional annotations.
## 2. Verification and documentation
- [x] 2.1 Test compatibility, total/grouping semantics, revoked access, legacy defaults and save/copy/publish behavior.
- [x] 2.2 Verify pie/donut, bars and area in the browser on dense and tall panels; confirm clean navigation and active-screen-only reads.
- [x] 2.3 Run frontend typecheck/test/build, affected backend checks, OpenSpec validation and structure checks; align docs before archive.

Acceptance evidence (2026-10-10): 73 dashboard frontend tests; typecheck and Vite build; dashboard-service 42 and telemetry-service 36 tests with no failures; OpenSpec 47/47 and structure checks. Live gateway smoke verified viewer scope and revocation. Desktop 1600x1000 and mobile previews covered area, colored same-unit comparisons, energy donut/bars and keyboard values. Evidence: local artifacts/demo-rich-*.log and desktop screenshot; generated artifacts are not committed. Maven completed with exit 0; telemetry test JVM emitted a shutdown timeout warning after passing its assertions.

Acceptance checkpoint (2026-10-10): implementation, current API/ADR documentation
and dependencies verified. Full backend `mvn -f backend/pom.xml test -q` completed
with exit 0: 454 tests, zero failures/errors/skips. Full frontend typecheck,
85 files / 265 tests and production build passed; ML unittest 62 tests and live
Docker training/artifact/inference smoke passed. Strict OpenSpec 47/47 and
structure checks passed before archive. Commands and scope are recorded in
`docs/current/flows/workspace-dashboard-business-verification.md`.
Generated logs remain local in ignored `artifacts/final-*` files.
Limitations: one passing Kafka test JVM required Surefire shutdown termination
after System.exit(0); existing structure/deprecation warnings remain. Local
synthetic energy profiling is not a production capacity claim. Native speech,
physical pointer drag and OS zoom are not claimed as newly exercised here.
