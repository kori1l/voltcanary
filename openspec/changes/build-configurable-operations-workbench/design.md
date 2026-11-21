# Design: Manual table, map, rule and evidence workbench

The [shared configurable architecture](../../../docs/target/configurable-operations-platform.md)
owns canonical semantics and service boundaries. Implementation begins with the
published native collection/row/LIVE owner profile; unsupported owners stay gated.

## Implementation Boundary

**Normative complete design (2026-11-03):**
Every delivery milestone includes manual UI and AI-compatible review; G6 is a module owner, not a prerequisite to postpone all AI until the end. Table queries avoid mandatory reference expansion. Show business-facing source/quality/approval controls; keep physical storage keys and AST details outside normal flows. Original saved receipts remain independent of later edits.

Contracts: [VCL](../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

Use schema/capability-driven feature modules and API clients; no business-specific giant route pages or hidden AI-only mutation path. UI labels describe business concepts while IDs/AST stay internal. Rule builder and optional expression editor compile to the same canonical graph. Preview uses actual authorized samples and explicit empty/stale/unknown states; schema edits show dependency impact. Browser rendering never drives evaluation.

## Compatibility and Validation

Preserve canonical resource IDs, signed access, existing arithmetic AST v1, current circle endpoints and saved reviewed plans. New schemas/operators must be versioned and advertised only when implemented. Preview and runtime use the same canonical contracts. Require independent cross-domain, negative, replay and access tests; a model explanation is not evidence of completion. Keep implementation tasks unchecked until code and verification exist.

## Existing Vehicles console and AI extension

### Product boundary

The 2026-11-18 correction makes the existing `/vehicles` page the vehicle
workspace. Extend it in place. Keep its fleet overview, map selection, selected
vehicle details, GPS source selection, Live measurements, Trips & playback and
Zones & settings. Do not create another transport dashboard or a configurable
circle widget to satisfy this scenario. General configurable tables and rules
remain available for other domains; reusable geometry code does not require a
second vehicle UI or a second monitoring owner.

### Starting point inspected on 2026-11-18

- `frontend/src/vehicles/VehicleConsole.tsx` discovers vehicles (currently capped
  at 100), searches by name, filters movement state and selects a vehicle from
  the existing list or `FleetMap`. It requires a GPS source choice when several
  REST sources exist. Fleet positions refresh every five seconds in the visible
  Live section; history/settings pause refresh and keep the last snapshot.
- `VehicleZones.tsx` edits the selected vehicle's speed limit, idle threshold
  and up to 50 named circular zones. Each has a stable UUID, WGS84 center and
  radius. Clicking `VehicleMap` moves the selected zone center. Manual numeric
  fields remain available. Limits are speed 1–300 km/h, idle 1–60 integer minutes,
  names up to 100 characters and radius 10–50,000 metres.
- `monitoringApi.ts` reads and replaces settings with GET/PUT
  `/telemetry/devices/{deviceId}/vehicle-monitoring`. Telemetry owns persistence
  in `telemetry.vehicle_monitoring`, keyed by `(user_id, device_id)`; these are
  personal monitoring settings for an authorized vehicle, not a new workbench
  identity or an account-wide shared geofence catalog. The repository currently
  upserts the full JSON document without a revision check or operation receipt.
  Controller authorization currently uses the existing device read-access check.
- `VehicleMap.tsx` renders existing route and zone layers but currently requires
  an actual vehicle position to initialize. A new zone can default to the last
  position or the existing fallback center; no-position map editing needs work.
- `VehicleHistory.tsx` and `monitoringModel.ts` derive trips and ENTER/EXIT/
  OVERSPEED views from recorded points and current settings. Gap handling avoids
  inferring transitions across discontinuities. These browser calculations are
  not persisted operational events, background rules or notification delivery.

### 1. Strengthen the existing monitoring owner

Keep telemetry-service as the single owner and preserve the existing user/device
key. Add a migration for monotonically increasing settings revisions, reviewed
definitions and durable mutation receipts. Initial existing settings receive an
explicit version; default settings also need an owner-issued base identity so
two first saves cannot both overwrite each other.

Extend the existing monitoring API family with preparation, confirmation and
receipt inspection. Preparation is read-only with respect to active settings:
authorize the actual caller/device, validate the complete candidate with the
same `VehicleMonitoringSettings` contract, read the base revision, and persist
an immutable review containing before/after settings, digest, expiry and owner
scope. Confirm only that review, with the expected revision and the original
idempotency identity. Commit settings, revision and receipt atomically. A stale
base returns a conflict and requires a fresh diff; it must not silently merge
two full documents. An exact confirmation replay returns its original receipt
without overwriting any subsequent manual edit.

Both manual and AI saves must pass this owner boundary; retire unconditional
replacement once the new contract is ready. Preserve current supported access
semantics for personal settings and explicitly test them. Account/device/source
references supplied by a model are never authority. Renew access before
confirmation, and authorize receipt reads. An uncertain confirmation offers
inspection of the same review/receipt; reload, polling or an expired review must
not dispatch another mutation. Decide exact DTOs/routes in owner implementation
and document them as implemented only after signed API tests exist.

### 2. Extend the existing Vehicles editor

Extract a focused monitoring draft/review model shared by `VehicleZones` and the
AI preview. Keep stable zone IDs and show add/update/remove changes, old/new
center and radius, speed limit and idle threshold. Preserve all omitted settings
and zones; deletion must be explicit. Provide review, edit, confirm, conflict and
unknown-outcome states inside the selected vehicle's Zones & settings workflow.

Reuse `VehicleMap` and `zoneGeometry` for before/after geometry. Allow zone-only
map initialization from the selected zone or an explicitly chosen map center
when GPS is missing; never fabricate a vehicle marker or call this center a
measurement. Show stale/no-data status separately. Do not change the current
fleet map, selection, route playback or source contracts just to add review.
Guard unsaved edits and out-of-order reads when changing vehicle/source. After
confirmation, refresh the owner settings for that same vehicle and reconcile
any newer local draft explicitly.

### 3. Add AI to this same workflow

Register a focused vehicle-monitoring skill in the existing assistant registry
and durable planning/execution pipeline (G7 integration), rather than treating
a zone request as CREATE_DASHBOARD. The application-level assistant overlay
receives explicit selected vehicle/source context from Vehicles. Server context
loading authorizes and resolves the concrete device, source and current settings;
an ambiguous vehicle, source or named zone produces a targeted clarification.
A request must not silently choose another vehicle or GPS source.

The skill proposes typed monitoring edits: add/update/remove a named zone by
stable identity, change radius/center, speed limit or idle threshold. The server
expands these edits against the exact base revision and uses the same telemetry
preparation as the manual editor. Do not let model text supply an arbitrary
endpoint, write JSON directly to storage or introduce another AI-only save path.
Missing coordinates require a question or user selection on the existing map;
they must not be guessed from a place name without an authorized supported
resolution path. Adding a circle cannot silently replace the existing zones.

Example: after selecting a vehicle, the user asks to add a depot zone of 200 m
around a chosen point and set the speed limit to 60 km/h. The assistant loads
that vehicle's current settings, prepares the exact diff, and displays it with
the existing map/editor review. Confirmation updates those settings. The result
links back to `/vehicles` with the exact selected vehicle and Zones & settings
context, using a validated route/state contract added to VehicleConsole. It
creates no dashboard record. Route restoration only reads; it never confirms.

Keep original reviewed definitions and receipt references in assistant history.
After a later manual edit, replaying the old AI confirmation returns the original
result and does not restore the old settings. Existing assistant budgets,
confirmation and uncertain-outcome controls apply; no second planner or ledger.

### 4. Truthful events and reusable geometry

The existing per-vehicle zones are inline monitoring settings; generic immutable
`/data/spatial-features` circles are separate reusable resources. Do not silently
replace one with the other or claim that their IDs/revisions are interchangeable.
Reuse geometry validation/rendering where contracts agree. If shared saved-zone
selection is subsequently required, review an explicit pinned reference and its
conversion into the vehicle owner's supported geometry; retain origin/version
and show the impact of future changes. Automatic catalog migration is not part
of this correction.

The initial AI scenario configures monitoring and opens its existing views.
It must describe current client-derived history accurately. Durable ENTER/EXIT
events, RULE activation and notifications require their actual event/rule owner
contracts and separate reviewed effects. Neither a configured zone nor an AI
explanation proves that alerts are running.

### 5. Delivery and acceptance

First remove the withdrawn widget and its AI creation path while preserving
general dashboard features. Then implement the monitoring owner revision/review
contract, update the manual editor, and integrate the AI skill with that same
preview/confirmation. Complete the slice before joint qualification.

Acceptance includes existing Vehicles selection/source/history regressions;
add/edit/remove zones and threshold changes through UI and AI; exact persisted
before/after parity; no GPS, stale points and gaps; conflicting manual/AI edits;
foreign user/device and receipt denial; unknown confirmation, reload and replay;
and desktop/mobile review rendering. A real-model scenario must result in the
selected vehicle's settings and receipt, with no new dashboard. Track model
quality separately from owner correctness. New tasks remain open until their
implementation and evidence exist; retired circle-widget evidence does not
qualify this scenario.


## Implemented Vehicles contract — 2026-11-18

The slice now uses telemetry V027 and ReviewedVehicleMonitoring for a single
personal user/device owner. `/snapshot` publishes revision/digest/settings;
`/reviews`, `/reviews/{id}` and `/reviews/{id}/confirm` implement immutable
30-minute proposals, typed validation, per-user/device serialization, CAS and
atomic original receipts. Settings GET stays readable for existing history;
unconditional PUT rejects writes. Version 0 represents absent settings and
migrated settings start at 1. Neither UI nor AI can bypass the reviewed write.

VehicleZones also supports explicit map-centre selection for AI through a
bounded vehiclePoint context fact. Selecting it opens the existing overlay and
does not prepare or save monitoring settings.

The ordinary VehicleZones editor and assistant render MonitoringReviewPreview
with the existing VehicleMap. Zone-only map initialization fits actual zone
boundaries without a fabricated vehicle marker. URL context restores selected
vehicle, registered source, section and original review. Dirty drafts prevent a
new AI result URL from replacing the active editor review until discarded.

`UPDATE_VEHICLE_MONITORING` is a focused registered skill wrapped around the
existing PrimaryDomain port. VehicleMonitoringDraft expands bounded edits while
preserving omitted zones/thresholds; VehicleMonitoringOwner uses ordinary signed
owner APIs. VehicleMonitoringExecutor confirms the stored exact owner review,
renews an explicitly selected GPS source before first dispatch, and reads only
on uncertain/expired recovery. Existing PlanStore, confirmation, graph runner,
ledger and conversation history remain authoritative. The result route is Vehicles.

The exact public DTOs, access/error behavior, navigation and reproducible smoke
are documented in [the monitoring contract](../../../docs/current/flows/vehicle-monitoring-verification.md).
Browser visual acceptance remains separate from API and component tests.

## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.
