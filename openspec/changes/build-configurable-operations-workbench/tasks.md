# Implementation and Verification

- [ ] 2.1 Integrate native/external capability UX, strict import manifests, partial/unknown outcomes and UI/AI canonical parity in every vertical slice.

- [ ] 1.1 Implement capability-driven collection/record/relation editors and existing business resource bindings.
- [ ] 1.2 Integrate generic spatial selector/editor and import mapping review.
- [ ] 1.3 Implement rule graph builder, dry-run evidence, dependency diff and activation UX.
- [ ] 1.4 Expose shared dashboard capabilities for configured records/derived values/evidence; integrate vehicle geometry and AI monitoring in the existing Vehicles console.
- [ ] 1.5 Verify desktop/mobile, access, unknown states and exact persisted equality with focused frontend/backend checks.


- [x] 3.1 Implement revision-2 review/count/NULL/UNKNOWN UI and verify A51/A63/A66 against owner APIs.

## 4. Native owner increments

- [x] 4.1 Implement the authenticated native table/row workbench with owner registry/list publication, reviewed schema/row writes, exact numeric/NULL handling, LIVE seek reads and recovery of the original saved review.

The /data console consumes the existing G1 reservation/review/apply/reconcile
contracts. Table creation, scalar/enum row authoring, independent deletion,
partial/unknown LIVE pages and URL-based review restoration are implemented.
The current API and supported form subset are documented in
[the native owner contract](../../../docs/current/api/configurable-native-data.md).
The current forms also support POINT/REF/INTERVAL and authoring ENUM choices.
Guided relation selection, maps, external modes, rule building,
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

Native import UI increment (2.1/3.1): show completed versus incomplete
validation, owner digest, committed and remaining rows, and an unknown outcome
after a failed confirmation or cancellation response. Inspect the same saved
review to recover, then refresh the LIVE row view on return. Focused frontend
tests cover saved-review recovery, partial results and the incomplete
validation gate. The signed Compose import smoke verifies atomic CSV insertion,
exact receipt replay and real worker progress with synthetic data.
Prepared import reviews show the format, mappings, empty handling and explicit
conversions from the sealed owner document. The same display works after a
reload and uses field IDs when a different schema revision is selected.

A63 row-budget exhaustion is persisted as a rejected incomplete validation
with exact examined/valid/invalid counts and unknown total/remaining counts.
The sealed plan contains no mutations and cannot be applied. Device-service
tests passed on H2 and PostgreSQL, including exact review replay and unchanged
target version, and the workbench test verifies the unknown counts and absent
confirmation action.

Signed HTTP verification adds A63 readback with explicit nullable counts,
A66 rejection of an added document and mismatched review digest/revision before
any data-version change, and A51 rejection of NULL for a required field with
unchanged LIVE rows. This complements the H2/PostgreSQL round trip proving
omitted nullable fields retain values and explicit NULL clears them.

Rule workbench increment (1.3): the /data rule pane edits node IDs, methods,
input edges and the terminal node through registry-driven selectors. Renaming
a node preserves its links. Scalar settings are generated from the same
registry, with structured expressions entered as typed JSON. The editor
preserves exact string-encoded durations when combining these inputs. The pane
prepares the canonical RULE through analytics,
using the current account, source node, coverage interval and proof. The
form can pin owned circle revisions from the catalogue. Sealed lookup
selections remain available through the advanced read-set JSON.
and displays the pinned source and reference digests. Prepared window aggregate
and interval union nodes can evaluate a recorded window through the owner dry-run
API and display the actual result and evidence references. The pane compares
successive owner checkpoint pins for the same rule and can prepare, confirm and
apply an owner cutover review. Pending activation can resume against the same
review while the pane remains open. Session dry runs use the prepared absence
node and optional owner coverage proof, with circle selection for zone sessions.
The result displays the returned entry, exit and witness evidence. Reload recovery
uses a per-actor, same-tab pointer and checkpoint, with explicit replay of the
original review and successor epoch. The activation form can compare the
prepared revision against the authorized predecessor checkpoint from analytics.
After a lost activation response, the editor can inspect the original successor
through that read-only route even when the confirmation review has expired.
It accepts only the same rule, epoch, bundle and checkpoint digest. The
frontend regression tests verify that this path dispatches no writes.
End-to-end verification remains open under 1.3.

Spatial UI increment (1.2): /data can create a circle and load an existing
circle revision by ID for review and revision. The map-based center picker
and exact decimal radius feed the existing owner API. A lost save response
can be inspected or retried against the same ID and expected revision.
The owner-scoped circle catalog pages current revisions. The same selector
loads circles into the editor and sets SPATIAL_FEATURE references in owned rows.

Resource binding increment (1.1): owned row editors can search devices and
locations by name and prepare the same canonical REF mutation as UUID entry.
The same row workflow can select sources, variables, operating schedules, and
operational events through the existing device and business resource APIs.

Native table authoring parity (2.1): the shared fixture now compares all
eleven manual and assistant field types, including ENUM, POINT, INTERVAL, and
self-reference. Both paths use the owner-reviewed VCL document and preview.
Import, rule and external-source parity remain open.

Dashboard increment (1.4): the owned table workbench can create a dashboard
with a pinned VCL/1 QUERY for selected columns. The dashboard manifest,
definition preview, and saved panel use the same bounded owner LIVE read and
display the returned rows, completeness and pagination boundary. Derived
values, geometry/evidence widgets, workspace access and end-to-end verification
remain open.

Combined verification: 444 Vitest cases and the frontend production build
passed. Maven reports are green for device-service (313), analytics-service
(638), dashboard-service (95, including the rerun of the updated catalogue
assertion) and api-gateway (25). OpenSpec passed 105/105. The signed Compose
smoke on the updated device and dashboard services verified circle pagination
and account isolation, matching LIVE rows in the draft and published dashboard
against the direct owner query, and rejection of foreign dashboard access.
The smoke used synthetic accounts without live model calls. It does not cover
the rule widget, browser viewport flows, external adapters or UI/AI parity.

Chromium on the local stack verified reviewed table creation, CSV review
restoration after reload, exact INT64 readback above the JavaScript safe
integer limit, and dashboard publication at 1440 px. At 390 px it verified
the published record, explicit NULL update and no page-level horizontal
overflow. The browser run also caught a stale draft guard after dashboard
creation. Successful creation now clears that guard before opening the editor.
Common-vcl tests verify that offset timestamps compile without a local timezone.

The same configured-records query builder drives the private dashboard
palette, panel settings, and create-from-table flow. Panel settings can
select a different owned table or repin columns after a schema revision.
The records panel can open a map for POINT values returned on the same page.
The table remains available with the exact values and the owner completeness
state. Rule-derived window values and their evidence are still pending.

The analytics owner exposes read-only lookup by account, deployment epoch and
measure UUID for finalized interval results. This rechecks deployment and
owner pins, returns the exact stored typed result, and caps its observation
references by the pinned policy. The private dashboard palette supports a
saved rule window result keyed by deployment and measure UUID. The shared
preview and saved-widget path fetches the result with current actor authority
and renders exact values, status and available observation references.
The widget editor lists finalized measures for a selected deployment with
owner-authorized pagination. Deployment discovery and runtime qualification
remain open.

The dashboard PostgreSQL integration suite verifies the configured rule
result through draft validation, preview, publication and foreign-actor
denial, with a controlled analytics response containing an exact duration
and evidence ID. The signed source-coverage smoke has an optional
`--execute-interval --verify-workbench` path that checks the real finalized
measure, predecessor checkpoint, catalogue, and draft/published readback.
The signed runtime path passed on the isolated `voltcanary-g6-rules` Compose
project on 2026-11-18 with workers enabled. It verified exact stored interval
value and observation references in both draft and published dashboards,
deployed-checkpoint and measure catalogue readback, and the underlying interval
correction and alert lifecycle. The default local project was untouched.

Native import capability discovery (2.1) reads the owner-published formats,
operations, and source and row-count limits for the validation manifest.
Focused frontend coverage verifies capability retry, oversized-file rejection
before upload, manifest limits, and saved-review access without capability discovery.

Saved chunked import reviews show terminal uncommitted counts and page through
the owner receipt journal. The focused recovery test verifies partial progress,
receipt pagination, and exact record version rendering after an uncertain apply.

The schema impact review lists affected rules, dashboards and alert grants
from owner dependency snapshots. The frontend verifies blocking entries
and requires a new review after an authoritative stale confirmation response.

Circle revision review queries analytics for matching immutable resource
pins and shows compiled bundles and staged or active deployments. Backend
coverage verifies the real compiled pin is returned by the owner-scoped
endpoint and that an account mismatch is rejected. Frontend coverage verifies
the dependency preview precedes the confirmed geometry write.

Circle dependency verification exposed a user/account identity mismatch in analytics circle
dependency discovery. The read now derives its account from the device
owner's authenticated circle response. Focused coverage uses distinct user
and account IDs and rejects a substituted circle identity.

Planner correction feedback for STEP_GOAL_MISSING now identifies the offending
step, its unknown references and the exact available retained/proposed goal
IDs before domain preparation. The existing graph validation remains
authoritative. Twenty-three planner projection/responsibility cases pass,
including unchanged rejected proposals, corrected references and rejection
of references to superseded goals.

Native table action discovery now publishes owner-scoped operations through
`/data/datasets/{id}/capabilities`. Record editing, imports, schema changes,
sharing, history and dashboard entry points use this snapshot. Tests cover
source read-only behavior, capability outage/retry, schema mismatch and native
owner capability publication. Controlled EXTERNAL mode coverage does not
qualify an external adapter implementation.


## 5. Reuse Vehicles for vehicle monitoring and AI (2026-11-18 correction)

The standalone configured-circle widget and saved-circle dashboard AI scenario
are withdrawn. Their earlier qualification does not satisfy Vehicles acceptance.
Reuse VehicleConsole, FleetMap, VehicleMap, VehicleZones, monitoringModel and the
telemetry monitoring owner. The existing generic spatial/rule APIs remain reusable.

- [x] 5.1 Remove the duplicate circle widget, its dashboard dependency API, AI data choices and smoke harness; verify existing dashboard and Vehicles regressions.
- [ ] 5.2 Extend telemetry vehicle monitoring with owner-issued revision/digest, durable reviewed before/after definitions, CAS confirmation, idempotent receipts and read-only uncertain-outcome recovery; migrate existing user/device settings without changing scope.
- [ ] 5.3 Extend the existing Vehicles Zones & settings editor with the shared review, conflict refresh and dirty-edit protection; enable selecting a zone center without GPS while retaining truthful vehicle freshness.
- [ ] 5.4 Add a typed vehicle-monitoring skill to the existing assistant registry/runtime. Resolve exact authorized vehicle and GPS source, ask on ambiguity, preserve omitted zones/settings and use the same owner preparation as manual editing.
- [ ] 5.5 Show the AI proposal in the existing Vehicles context with before/after geometry and settings, explicit confirmation and a link back to the selected vehicle/section; retain the application assistant overlay and original review after later edits.
- [ ] 5.6 Qualify manual/AI parity, add/edit/remove zones, radius limits, no GPS/stale/gaps, source ambiguity, concurrent edits, foreign access, lost ACK/reload and duplicate confirmation. Verify desktop/mobile and selected vehicle/source preservation.
- [ ] 5.7 Run the actual Vehicles AI scenario and record owner receipts/readback separately from model quality; do not count old circle-dashboard runs or client-derived events as durable alerts. Keep wider G6/G7/G8 gates open.

Removal verification: 54 focused AI backend cases and 32 dashboard backend cases
(including PostgreSQL) pass. Frontend runs pass 47 Vehicles/editor cases and
102 dashboard cases (overlapping selections), with typecheck and production build.
OpenSpec validates 105 items; the structure check passes with existing warnings.
The new Vehicles AI workflow is not yet implemented or visually qualified.
