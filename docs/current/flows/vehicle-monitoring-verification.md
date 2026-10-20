# Vehicle monitoring console

The /vehicles console expands the basic GPS dashboard with a fleet map, source
selection, observed trips, recorded-sample playback, CSV export, owner zones and
configurable vehicle simulation. The dashboard vehicle-map stays compatible.

## Contracts

- POST /telemetry/vehicles/positions: `{targets:[{deviceId,sourceId}],asOf}`,
  1–100 targets. One authenticated internal device-service batch validates every
  current owner/type/source before any positions are returned. Invalid/foreign
  bindings fail closed; no partial access is silently accepted.
- GET /telemetry/devices/{id}/positions/recordings: sourceId/from/to/cursor/limit,
  positive range <=31days, ascending exclusive timestamp cursor, immutable to,
  limit1–2000/default1000. `{points,nextCursor,hasMore}`; null cursor when complete.
  Browser loads at most20000 samples and displays truncation.
- GET/PUT /telemetry/devices/{id}/vehicle-monitoring: authenticated current owner,
  `{speedLimitKmh,idleMinutes,zones}`. Limit1–300km/h, idle1–60min, <=50 unique UUID
  circles, name1–100chars, radius10–50000m and finite geographic bounds. Settings
  are keyed by owner/device and replaced atomically. Dashboard grants do not
  authorize writes. Portable V011 adds nullable ignition/fuel_percent/odometer_km
  to accepted positions and a settings table; position retention stays180days.

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

## Evidence — 2026-10-11

Implementation and stack acceptance in progress; final results are recorded after
verification. Basic GPS acceptance is tracked in vehicle-gps-verification.md.
