# Vehicle monitoring console

The /vehicles console expands the basic GPS dashboard with a fleet map, source
selection, observed trips, recorded-sample playback, CSV export, owner zones and
configurable vehicle simulation. The dashboard vehicle-map stays compatible.

## Contracts

- Fleet discovery pages the authorized VEHICLE inventory and excludes
  DECOMMISSIONED devices before applying the 100-vehicle display limit. Offline
  and disabled devices remain eligible. A partial inventory read failure is
  reported as an error; it is not presented as a complete fleet. Reload fleet
  refreshes device/source discovery after a new source is connected.

- POST /telemetry/vehicles/positions: `{targets:[{deviceId,sourceId}],asOf}`,
  1–100 targets. One authenticated internal device-service batch validates every
  current owner/type/source before any positions are returned. Invalid/foreign
  bindings fail closed; no partial access is silently accepted.
- GET /telemetry/devices/{id}/positions/recordings: sourceId/from/to/cursor/limit,
  positive range <=31days, ascending exclusive timestamp cursor, immutable to,
  limit1–2000/default1000. `{points,nextCursor,hasMore}`; null cursor when complete.
  Browser loads at most20000 samples and displays truncation.
- GET /telemetry/devices/{id}/vehicle-monitoring retains the current settings
  read for history. `/snapshot` returns `{revision,digest,settings}`. An absent
  settings row has revision 0; migrated existing rows start at revision 1.
  Settings remain personal to `(authenticated user, authorized device)`.
- POST `/telemetry/devices/{id}/vehicle-monitoring/reviews` accepts exactly
  `{expectedRevision,idempotencyKey,settings}`. Settings have speedLimitKmh 1–300,
  integer idleMinutes 1–60, and at most 50 unique UUID circles with nonempty names
  up to 100 characters, WGS84 coordinates and radius 10–50,000 m. Numeric strings,
  fractional idle values and unknown fields return 400. Preparation stores an
  immutable before/after review, without changing active settings. The key is
  1–100 ASCII letters/digits/underscore/hyphen; reuse with different content is 409.
- GET `.../reviews/{reviewId}` reads that user's review for that device. It
  returns id, deviceId, baseRevision, before, after, digest, idempotencyKey,
  expiresAt, status (PREPARED/SUCCEEDED/EXPIRED), appliedRevision (nullable).
- POST `.../reviews/{reviewId}/confirm` accepts exactly `{digest,idempotencyKey}`.
  It renews device access, serializes per user/device and checks the original
  base revision. Settings, next revision and the review's appliedRevision commit
  atomically. Stale bases or changed confirmation are 409; uncommitted expired
  reviews are 410. Reviews expire after 30 minutes, measured by the owner clock.
  Confirmed reviews retain their original result even after later manual edits.
  GET inspection never writes, renews the lease or dispatches a replacement.
- Unconditional PUT is retired and returns 400. Both manual and AI settings writes
  use the review boundary. Device read authorization is reused for these personal
  settings; dashboard grants do not confer device access. Unsigned identity headers
  are rejected. Foreign/unavailable review scope returns 403/404. V027 adds versions,
  per-user/device fences and durable reviews; position retention stays 180 days.

## Shared Vehicles and AI workflow — 2026-11-18

Vehicles retains its original fleet map/list, GPS source selection and history.
`/vehicles?deviceId=<uuid>&dataSourceId=<registered-source-uuid>&section=ZONES&reviewId=<uuid>`
restores the selected context and reads the original review; navigation never
confirms. The manual Zones & settings editor reads a base snapshot, shows the
shared MonitoringReviewPreview, then confirms. Conflict reload is explicit;
unknown saves offer inspection first. Dirty drafts warn before vehicle/source/
section changes or unload. Zone editing initializes the existing VehicleMap
without GPS, with no fabricated vehicle marker; numeric fields work if tiles fail.

The **AI monitoring** button opens the existing application assistant overlay
with the exact vehicle and selected registered GPS source. In Zones & settings,
**Choose a point for AI** lets the user click the same map and opens the overlay
with that explicit bounded centre, without changing settings. The point is passed
as user context, never as a vehicle observation. The registered
`UPDATE_VEHICLE_MONITORING` skill uses the same planner/ledger/PlanStore and
telemetry preparation. Commands carry the exact expectedRevision, optional
threshold changes and bounded ADD/UPDATE/REMOVE zone edits. Omitted fields and
zones are preserved; new zone UUIDs are server assigned. Vehicle/source IDs are
renewed against current owners. Multiple sources without an explicit UI selection
require selecting a source in Vehicles. Unknown/ambiguous zones or coordinates
require clarification, not invented geography. Commands cannot replace the entire
zone list or create a dashboard. The assistant and manual editor render the same
before/after settings/map component. Confirmation stores the selected vehicle's
settings and returns a Vehicles link. Recovery of UNKNOWN/expired AI plans only
inspects the original owner review and cannot confirm again. Successful confirmation
replay does not overwrite later manual edits.

These settings drive the existing client-derived history views. They do not create
persisted operational events, activate RULEs or enable notification delivery.

Run `python scripts/ai-vehicle-monitoring-smoke.py` against the app profile for
an isolated deterministic-provider scenario through ordinary authenticated APIs.
`--live` uses VOLTCANARY_DEMO_EMAIL/PASSWORD and the account's configured model;
it does not alter provider settings. Both modes use a disposable vehicle and remove
it afterward. Reports under ignored artifacts contain no credentials/provider bodies.
Live model quality and browser visual verification must be reported separately.

## Simulation extension

Existing CITY_LOOP YAML remains valid. Optional gps.waypoints contains2–100
latitude/longitude/stopSeconds objects; finite coordinates, integer stops0–3600s,
nonzero closed route. Last connects to first. Optional gps.vehicleMetrics=true
requires generated ignition, fuelPercent, odometerKm alongside the original four
metrics. Defaults: initialFuelPercent80, initialOdometerKm0, fuelTankLitres50,
consumptionLitresPer100Km8. Accepted optional fields: ignition0/1, fuelPercent0–100,
odometerKm0–10000000. Dwell emits zero speed/off ignition. Synthetic fuel decreases
with simulated distance; exhaustion stops motion. Odometer resets to its configured
initial value on each run. Changes require draft validation/save/explicit restart.
Pinned yaml2.9.1 Document methods preserve unrelated YAML fields/comments.
Credentials never enter YAML or browser drafts.

## Evidence interpretation

Trips and ENTER/EXIT/OVERSPEED events are derived from loaded accepted samples
under current settings. Gaps>60s, non-increasing time or implausible>300km/h
movement split coverage. Initial membership creates no invented zone entry.
Speed>3km/h starts motion; configured sustained idle ends a trip. Distance sums
plausible observed haversine segments, not certified odometer distance. Playback
seeks actual samples; it never invents positions across gaps. At most1000 recent
samples are drawn in its trail. CSV includes UTC time, coordinates, speed/course
and optional vehicle readings; unknown is empty, zero remains0.

Events are evidence, not durable notification delivery. Hardware adapters, driver
identity, dispatch, remote engine commands and third-party notifications require
separate integrations and are not implied by this console.

## Acceptance procedure

1. Start updated device/ingestion/simulator/telemetry/dashboard/frontend services;
   wait for health (Compose Java builds are serialized). Install the lockfile in
   the frontend node_modules volume.
2. Open Vehicles in the demo account; verify source identity, statuses, timestamp,
   fleet selection/search/filter and 5s visible refresh.
3. Load historical range, seek/play/pause/change speed, select a trip, inspect
   events, export CSV; later live points must not enter the fixed snapshot.
4. Add a circle, set centre using map or coordinate fields, save and reload.
   Invalid settings and foreign device requests must fail.
5. Load a city route with stops in Demo Tools, enable vehicle fields, validate/save,
   explicitly stop/start; confirm zero-speed dwell, ignition, fuel and odometer.
6. Verify original dashboard/card, responsive layout and failure fallback.
   Leaving Live pauses fleet polling; hide/unmount aborts reads. Errors are explicit
   and never substitute another source's evidence.
7. Run backend tests, frontend typecheck/test/build, openspec:validate and
   structure:check. Record actual results and limitations below.

## Evidence — 2026-10-21

Authenticated Docker acceptance passed using
`scripts/vehicle-monitoring-docker-smoke.ps1` against the running app profile.
The ignored `artifacts/vehicle-monitoring-docker-summary.json` records45 assertions
(including23 exclusive page boundaries),119 accepted samples across24 pages,
foreign/mixed-owner denial, fleet/range/page bounds, valid owner settings and
rejected writes preserving the previous settings. A separate fixed-cutoff read
retained the same119 samples while later live observations arrived. Disposable
foreign/no-data devices were decommissioned; the demo vehicle remains available.

The browser explicitly saved a three-point route with scheduled10s stops and
vehicle metrics, preserving the running snapshot until Stop/Start. The new run
emitted measured zero/off ignition during dwell,36km/h during movement,
decreasing synthetic fuel and increasing run-relative odometer. Saving alone
did not restart the run. The original dashboard map remains compatible; its
basic GPS and map-failure acceptance is recorded in
[vehicle GPS verification](vehicle-gps-verification.md).

Browser fleet acceptance covered search, status filters, source selection and
NO_DATA without substituting another vehicle's coordinates. History paused
live refresh and loaded639 actual samples at a fixed cutoff; playback at20x,
seek to sample639, pause and trip selection used recorded timestamps. The
downloaded CSV contained639 rows and8 columns, preserving zero values and empty
unknown optional fields. Observed trip distance, incomplete coverage and
ENTER/EXIT/OVERSPEED evidence were displayed with their historical-evidence
disclaimer. Local reports and CSV remain ignored artifacts.

The zone name and longitude were edited in the browser, explicitly saved and
verified after page reload and through the owner API. At390px viewport width,
the zone editor and maps were usable with375px document/client width and no
horizontal overflow. Returning to Live resumed refresh with newer timestamps.
After the user's Docker stop, the app profile was restored with preserved volumes
and the simulator explicitly restarted; fresh accepted points were verified.

Docker Java21 tests passed across all backend modules:594 unique tests in the
final service runs, including85 telemetry tests after the strict JSON fix.
The regression uses the real signed identity filter and H2 persistence to check
fractional/unknown fields, invalid writes, owner persistence and foreign/header-only
access. Frontend Docker validation passed typecheck,113 files/327 tests and build;
the subsequent AI navigation fix passed7 focused tests plus typecheck/build.
Do not interpret the earlier failed intermediate runs as successful gates.

The live dependency-shutdown probe was rejected by automatic approval review
(`blocked by policy`) and was not executed. Dependency failure isolation is
covered by Docker query tests and the live map-style failure check; a live
telemetry-service outage is not claimed as verified.
