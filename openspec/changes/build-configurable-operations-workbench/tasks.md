# Implementation and Verification

- [ ] 2.1 Integrate native/external capability UX, strict import manifests, partial/unknown outcomes and UI/AI canonical parity in every vertical slice.

- [ ] 1.1 Implement capability-driven collection/record/relation editors and existing business resource bindings.
- [ ] 1.2 Integrate generic spatial selector/editor and import mapping review.
- [ ] 1.3 Implement rule graph builder, dry-run evidence, dependency diff and activation UX.
- [ ] 1.4 Expose shared dashboard capabilities for configured records/derived values/geometry/evidence.
- [ ] 1.5 Verify desktop/mobile, access, unknown states and exact persisted equality with focused frontend/backend checks.


- [ ] 3.1 Implement revision-2 review/count/NULL/UNKNOWN UI and verify A51/A63/A66 against owner APIs.

## 4. Native owner increments

- [x] 4.1 Implement the authenticated native table/row workbench with owner registry/list publication, reviewed schema/row writes, exact numeric/NULL handling, LIVE seek reads and recovery of the original saved review.

The /data console consumes the existing G1 reservation/review/apply/reconcile
contracts. Table creation, scalar/enum row authoring, independent deletion,
partial/unknown LIVE pages and URL-based review restoration are implemented.
The current API and supported form subset are documented in
[the native owner contract](../../../docs/current/api/configurable-native-data.md).
POINT/REF/INTERVAL authoring, relations/maps, external/import modes, rule building,
AI tools and complete cross-domain M1 qualification remain pending. Broad tasks
above deliberately stay unchecked; this increment is not full G6 acceptance.

Verification:199 focused backend cases (including real H2/PostgreSQL signed HTTP)
and424 frontend cases across135 files pass. Typecheck/build pass. The initial
parallel frontend run had one existing ML timeout; its23 cases pass independently
and the full two-worker rerun passes. Browser visual checks use a disposable
component fixture on desktop/390px, separately from owner API correctness.
Logs are ignored under artifacts/native-workbench-*.
Final focused verification passes22 native/recovery-route cases after checking
that login return context preserves dataset/review IDs and drops credentials.
OpenSpec104/104, structure and VCL documentation checks pass.
