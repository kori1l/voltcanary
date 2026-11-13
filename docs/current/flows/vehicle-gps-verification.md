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

## Revalidation — 2026-10-21

The resumed Docker app stack passed the current full backend module sets
(591 tests), frontend baseline (113 files / 327 tests), typecheck and build.
The later assistant-only navigation fix passed seven focused tests and the
same typecheck/build gates. Real PostgreSQL/Timescale tests cover migrations,
atomic positions, duplicate/source/time boundaries and bounded history.

`scripts/vehicle-gps-acceptance.ps1` passed twelve authenticated checks: optional
location, saved GPS profile, moving accepted positions at36km/h, historical asOf,
24h limit, unknown/foreign source access, ordinary published widget evidence,
Stop/STALE with the last point retained, explicit restart at the route origin,
and accepted appliance telemetry. The existing FRIDGE preset is electrical:
its regression asserts registered activePowerW/W rather than inventing a
temperature metric. The initial wrong temperature assertion is retained as a
failed test-assumption log. The final run passed and decommissioned its disposable
appliance. The named GPS demo remains running for monitoring acceptance.

Browser verification showed real streets, heading marker, observed trail and
attribution; manual panning disabled Follow. A390px viewport had no horizontal
document overflow and the viewport override was reset. The published map retained
its device/source/layout on a fresh navigation. An adjacent ordinary speed panel
showed36km/h. Temporarily setting the Docker frontend style URL to a missing local
JSON produced an explicit map error/Retry while coordinates, timestamps and the
independent speed panel remained available. The original OpenFreeMap style was
restored immediately. Screenshots and reports remain under ignored artifacts.

The pinned dependency is MapLibre6.14.0. The existing public style and visible
attribution were checked against [OpenFreeMap documentation](https://openfreemap.org/)
and its [current service terms](https://openfreemap.org/tos/). There is no offline
tile downloader or availability guarantee in this feature. The later accepted
`hsl-live-vehicles` main spec already owns explicit static fleet bindings;
the baseline single-source validation remains compatible with that extension.

`scripts/vehicle-gps-dashboard-acceptance.ps1` also passed: a25h map request
returns an isolated LIMIT_EXCEEDED while the speed panel remains ready; fixed
historical queries exclude later positions; ordinary Copy retains source/device,
version, screen and map height without publishing the copy. The copy was archived.
An attempted live shutdown of telemetry-service was rejected by automatic approval
review with only `blocked by policy`; no shutdown ran. Dependency-failure isolation
is covered by the Java21 Docker query tests; that live outage case is not claimed.
