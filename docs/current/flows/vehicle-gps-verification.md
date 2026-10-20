# Vehicle GPS and map verification

The baseline is one automobile, one simulated GPS source, CITY_LOOP, observed
position/trail and a single-device dashboard widget. No OBD, fleet map, trip
playback, geofencing, routing service or vehicle ML is included.

## Local prerequisites and provisioning

Run the existing Compose app stack. After updating source, recreate/restart
device, simulator, ingestion, telemetry, dashboard and frontend services. Java
Compose services compile mounted code on startup; wait for health before API
provisioning. Install the updated frontend lockfile inside the frontend container
(`docker compose exec -T frontend npm ci`) if its existing node_modules volume
predates MapLibre. VITE_MAP_STYLE_URL configures a public credential-free style.

Use a demo account; supply credentials to the process, never commit them:

```powershell
./scripts/vehicle-gps-docker-smoke.ps1 -Email $demoEmail -Password $demoPassword
```

The script creates/reuses the exact named Demo car and Vehicle GPS dashboard in
the signed account, validates/saves GPS config, starts the simulator, validates
the widget and publishes. It does not change favorites/personal default, grants,
other devices or existing compatible configurations. Its demo config uses 24h
duration so it remains viewable; the product template stays 30m. It prints only
device/source/dashboard identities and URLs, not tokens or passwords. Duplicate
names are rejected. The script intentionally preserves provisioned demo records.

## Acceptance

1. Verify device VEHICLE, one SIMULATOR/REST source and VEHICLE_GPS/CITY_LOOP.
2. Start, wait for at least three accepted points; verify changed coordinates,
   recorded timestamp, 36km/h and course. Open Movement and the published map.
3. Reload: binding/layout remain; map dashboards default to 5s auto-refresh,
   and the browser-local refresh choice survives subsequent reloads.
4. Stop: last position stays, no new observations; after age30s it is STALE.
   Start again: contour resets; a missing/impossible transition is not a trail.
5. Inspect map/source/period settings, Follow/manual pan and responsive layout.
   Change screen or hide the tab: position reads stop. Unmount frees map objects.
6. Fail WebGL/tiles/style: error/Retry and textual GPS remain. Fail telemetry:
   explicit refresh error; other dashboard panels continue independently.
7. Foreign device/source/revoked grant cannot disclose coordinates. Historical
   asOf excludes later points; >24h is a map-panel limit; >1000 points is truncated.
8. Verify ordinary fridge creation/preset/simulator/chart remains supported.

## Automated validation

Backend: `cd backend; mvn test` (shared event/web contracts changed).
Frontend: typecheck, test, build. Repository: openspec:validate, structure:check.
Tests cover route geometry/profile validation, accepted atomic storage/duplicates,
time/source/range/bounds, dashboard query source/cache/filter/isolation, trail
gaps, map failure and existing appliance workflows. Existing Timescale/Postgres
integration tests run migrations; runtime checks exercise the real GPS pipeline.

## Evidence — 2026-10-11

The requested demo automobile and published Vehicle GPS dashboard were provisioned
and reused without duplicates. Real stack accepted positions move every5s at36km/h;
the map renders streets, attribution, course marker and observed trail. Movement
card and dashboard were inspected in the signed demo account. Stop retained the
last point and produced STALE after37s; explicit restart returned RUNNING at the
origin. Historical asOf returned the earlier accepted sample; foreign source,
unknown device and >24h requests returned400/403/400. Mobile390px viewport had no
horizontal document overflow. Follow toggle and map failure textual fallback were
observed. Tests exercise the remaining geometry, storage/caps/filter/cache and
appliance regression cases. Full backend reactor passed; frontend96files/291tests,
typecheck/build and repository validation passed before the subsequent expansion.
Physical tracking devices and external notification providers were not tested.
