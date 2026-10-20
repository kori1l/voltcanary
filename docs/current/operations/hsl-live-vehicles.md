# Real Helsinki vehicles (HSL HFP)

The optional connector subscribes to real, public ongoing tram GPS observations
from `mqtt.hsl.fi:8883` over verified TLS. It does not use the simulator.
Source documentation: [HSL HFP](https://digitransit.fi/en/developers/apis/5-realtime-api/vehicle-positions/high-frequency-positioning/).
Attribution: HSL HFP open data. Anonymous MQTT access was verified on 2026-10-11;
this does not imply anonymous access to the other Digitransit HTTP APIs.

## Setup

Start the normal local app stack first. From the repository root:

```powershell
docker compose -f docker-compose.yml -f deploy/docker-compose/hsl.yml --profile hsl build hsl-connector
$ownerCredential = Get-Credential
./scripts/connect-hsl-vehicles.ps1 -Email $ownerCredential.UserName -Password $ownerCredential.GetNetworkCredential().Password
```

Initial setup discovers three currently active GPS trams (up to 45 seconds),
creates owner-bound VEHICLE devices and API/REST sources, and publishes a
`Helsinki HSL` dashboard with **one common map**. Repeated setup reuses the pinned
vehicles, credentials and dashboard. `-Count` accepts 1-20; a larger count adds active vehicles while retaining all
existing identities. Shrinking is refused. Omitting the count reuses the saved
allowlist size. Setup recreates the subscriber to load its private configuration.
Discovery may find too few vehicles outside operating hours.

When the owner's dashboard quota is already full, `-DashboardId <owned-id>`
explicitly repurposes that dashboard into the HSL map using guarded draft save
and publication; old published revisions remain recoverable. Default setup does
not remove another dashboard or silently increase quota.

Private connector configuration lives in ignored `docker-data/hsl/config.json`.
It contains per-source ingestion tokens, never the owner's password or JWT.
The container reads it through a read-only mount, runs as an unprivileged user,
has a read-only filesystem, and exposes no ports. Protect this directory like
any device credential. Use ordinary source-token rotation and update its local
`sourceToken` value when revoking/replacing a token; restart the connector.

```powershell
docker compose -f docker-compose.yml -f deploy/docker-compose/hsl.yml --profile hsl up -d --no-deps hsl-connector
docker compose -f docker-compose.yml -f deploy/docker-compose/hsl.yml --profile hsl logs --tail 30 hsl-connector
docker compose -f docker-compose.yml -f deploy/docker-compose/hsl.yml --profile hsl exec -T hsl-connector cat /tmp/hsl-status.json
docker compose -f docker-compose.yml -f deploy/docker-compose/hsl.yml --profile hsl stop hsl-connector
```

## Contract and limits

- Stable identity is the topic operator (four digits) and vehicle number (five
  digits), independent of route/direction. Payload `oper` may differ and is not
  used as vehicle owner identity.
- Exact subscriptions: `/hfp/v2/journey/ongoing/vp/tram/OOOO/VVVVV/#`.
- Only `VP` payloads with `loc=GPS`, finite valid coordinates and timezone-aware
  timestamps from the last 120 seconds (at most 15 seconds ahead) are accepted.
  Retained messages, duplicate/out-of-order timestamps and unsupported modes
  are ignored. Non-GPS calculated/manual positions are skipped.
- `lat`, `long`, `tst` become latitude, longitude and observation timestamp.
  `spd` is converted from m/s to km/h; heading 360 becomes zero. Invalid/missing
  speed and heading are omitted. Fuel, ignition and odometer remain absent:
  HSL's `odo` is an unreliable trip counter, not vehicle lifetime mileage.
- At most one latest pending point per configured vehicle, at most 20 vehicles,
  forwarded through normal authenticated `/ingestion/readings` every five
  seconds. Slower ingress can lower forwarding frequency. No raw-feed backlog
  or durable retry queue is kept; intermediate positions/outage samples may be
  lost. No interpolation is stored and timestamps are never refreshed locally.
- MQTT reconnect delays are 1-30 seconds; HTTP attempts time out after three
  seconds and keep only the newest fresh sample for retry. Deleted/revoked
  bindings fail through ordinary ingress authentication. Logs omit credentials.
- Existing latest evidence becomes STALE after 30 seconds without observations.
  Staleness can mean an inactive service journey or source outage, not necessarily
  a broken tracker. Silent vehicles keep their stable device identity.
- Health status reports MQTT connection, heartbeat, accepted count, last accepted
  time and pending count. A missing/disconnected or >90-second heartbeat is
  unhealthy; this check alone does not guarantee ingestion is accepting data.
- One connector instance is supported for this local allowlist. This optional
  Compose integration is not a Kubernetes/Helm rollout or public-feed admin UI.

## One dashboard map

`vehicle-map` supports the legacy one-device `query.sourceId` and an alternative
`query.vehicleSources` object mapping device UUIDs to external source IDs.
Fleet mode requires a FIXED binding with exactly the same 1-20 device UUIDs.
The typed internal `/internal/dashboard/positions` request carries this map and
the existing signed owner scope. All devices and sources are checked before
any latest position is read. `positions.fleet` contains per-device latest
evidence with observation time, source and freshness. Runtime scope restrictions
filter targets; shared definitions remove unauthorized source identifiers.
GPS queries bypass query caching. Fleet mode shows current markers and recorded tracks from the last 15 minutes,
bounded to 1000 points per vehicle. Colored lines show observed segments; faint
straight connectors join gaps without claiming the path between those points
was recorded. Tracks are visible by default, selectable and can be hidden.
Individual movement/history views also provide trips and playback.

The common map initializes to all available markers, lets the user choose a
marker or vehicle name, and offers fit-all and follow-selected controls. It has
no API client or refresh timer of its own; existing dashboard refresh drives it.
If basemap loading fails, vehicle measurements remain available as text.

## Verification

```powershell
docker run --rm voltcanary-hsl-connector python -m unittest -v test_hsl
./scripts/verify-hsl-vehicles.ps1 -Email $ownerCredential.UserName -Password $ownerCredential.GetNetworkCredential().Password -ExerciseOutage
```

Verification checks all configured vehicles have advancing real source
timestamps, absent fabricated metrics, STALE evidence after stopping the
connector for 37 seconds, and FRESH evidence after restart. The connector is
restored in a `finally` block. Sanitized evidence is saved in ignored
`artifacts/hsl-live-summary.json`.

Observed local evidence on 2026-10-11: topic identities `0040-00412`,
`0040-00423`, `0040-00437`, API/REST sources, advancing GPS observations and
stop/stale/restart passed. The demo user's previous Vehicle GPS dashboard was
repurposed because all ten dashboard slots were occupied; its historical
revisions remain available. The allowlist was subsequently expanded to ten trams at the user's request.
One-map publication and browser verification are completed alongside this
change's task checklist.

Final verification: ten configured devices were FRESH with advancing source
timestamps, one published common map (revision 4) displayed ten markers in the
browser, marker selection and fit-all worked. All backend reactor tests passed;
focused fleet query/runtime-scope tests passed after the final manifest update.
Frontend: 102 files / 300 tests, typecheck and production build passed. Seven
connector tests, strict OpenSpec validation and structure checks passed.
Sanitized screenshot: `artifacts/helsinki-hsl-fleet.jpg` (ignored).

Fleet track follow-up (2026-10-11): the live dashboard returned ten histories
and over 1500 real GPS points for the final 15 minutes. Browser verification
confirmed colored recorded lines, selected-track highlighting and the Show
tracks toggle. Missing intervals use faint straight endpoint connectors.
Screenshot: `artifacts/helsinki-hsl-tracks.jpg` (ignored). Focused backend and
frontend tests, typecheck, build, strict OpenSpec and structure checks passed.

The fleet dashboard lists vehicles in a bounded table (name, freshness, speed
and observation time). Selecting a row or marker updates the same selection.
Fit-all, track visibility, follow-selected and the track explanation are in a
collapsed settings overlay at the map top right. Escape or outside click closes
the overlay; the table remains available while the map updates. The table itself starts
collapsed and opens through its compact Vehicles/count toggle; selection is
preserved when it is closed.


## Stored journey context

Accepted GPS snapshots now include an optional `transport` object, persisted in
`telemetry.positions.transport_context` and the valid raw reading JSON. Latest,
recordings, trail and fleet APIs return it with the original observation time.
Fields: routeId, routeShortName, direction, destination, nextStopId,
scheduleDeviationSeconds, doorsOpen, accelerationMs2, scheduledStart and
locationSource. Destination/next-stop identifiers come from the explicit HFP
topic; nextStopId is not a stop name. Scheduled start is HSL's local HH:mm.
The schedule offset retains the original HSL `dl` sign: negative means behind
the timetable, positive means ahead, according to HSL documentation.

Each sampled VP contains its own context; missing values are omitted by the
connector and returned as null rather than carried forward. Older observations
have null context. Selected vehicle details display available values. These
are sampled 5-second VP snapshots, not a complete door/stop event log. Context
is bounded and validated before accepted projection; it is excluded from the
numeric measurement catalog and validated analytics event. Migration V012 is
portable for H2 and Timescale/PostgreSQL. No historical journey backfill is
fabricated.

On 2026-10-12, fixture tests verified HTTP acceptance/rejection, broker-context
validation, raw-reading retention, position/history roundtrip, false and zero,
missing fields and migration. The deployment connected to HSL with refreshed
per-source tokens; all ten previous positions remained readable. At nighttime
verification no fresh messages were received from the configured allowlist, so
live context values remain unverified until service resumes. New context is
collected automatically by the running connector; no user action is needed.

A read-only wider HFP observation at 2026-10-11 23:58 UTC (2026-10-12
02:58 Helsinki time) received nine bus and twenty train identities in twenty
seconds, including buses 97N, 570N, 600 and 30. A separate forty-five-second
tram discovery received no fresh GPS tram positions. These bounded observations
are not a fleet census or proof that trams never operate at night. The existing
ten-tram allowlist was preserved; buses were not provisioned by this check.

Verification completed: eight connector tests, shared validation and service
integration tests, the backend reactor covered across the original run and
successful telemetry/downstream reruns after correcting the test timestamp
precision, focused frontend tests, typecheck, build, strict OpenSpec and
structure checks. Live context ingestion remains explicitly unobserved during
the configured trams' silence.

## Night buses alongside trams

The connector supports explicit `mode: tram` or `mode: bus` bindings; missing
mode retains the legacy tram meaning. Bus source IDs are `hsl-bus-OOOO-VVVVV`.
Mode is part of the internal mailbox identity, so a bus can never overwrite a
tram with the same numerical operator/vehicle pair. The combined fleet remains
bounded to twenty devices. Only ongoing fresh, non-retained GPS VP is accepted.

Provision with `scripts/connect-hsl-vehicles.ps1 -Email <owner> -Password <password>
-BusCount 5` to retain previous trams and select five currently active buses.
`Count` means desired tram count; `BusCount` means desired bus count. Omitted
counts reuse the saved counts. `BusRoutes` defaults to 97N, 570N, 600 and 30;
these are discovery filters, not a promise of future route assignment. Explicit
devices continue to be followed when their route changes. Repeated runs reuse
devices, source keys and the common dashboard. Shrinking is refused. Both
vehicle modes display their own upright 18px glyph inside the existing 32px
marker. Table and map settings remain collapsed by default.

Use `scripts/verify-hsl-vehicles.ps1 -Email <owner> -Password <password> -Mode bus`
to verify active buses without requiring sleeping trams to be fresh. `ALL`
remains the default; `tram` is also supported.

Real verification on 2026-10-12 at 02:08 Belgrade time: five buses (three 570N,
two 600) were FRESH with advancing source timestamps. Each had eleven persisted
GPS/history observations including real route, direction, destination, next
stop, schedule offset, doors, acceleration, scheduled start and location source.
Three buses had eleven distinct coordinates; another had two and one was
stationary. Missing ignition/fuel/odometer remained null. The published common
dashboard revision 6 contained fifteen bindings. Repeat provisioning kept the
same fifteen identities and revision. Sanitized evidence is in ignored
`artifacts/hsl-night-live-summary.json`. This verifies real context ingestion
for buses and supersedes the earlier absence of live context evidence, while
the ten trams remain silent.

Browser verification confirmed fifteen markers: five bus glyphs and ten tram
glyphs, selected bus 570N with live journey details, and recorded tracks. The
table and map settings remained collapsed. Screenshot:
`artifacts/helsinki-night-buses.jpg` (ignored). Nine connector tests and sixteen
focused frontend tests passed, as did typecheck, production build, strict
OpenSpec validation and structure checks. No AGENTS.md or skill changes are
required because the repository workflow and service boundaries are unchanged.

## Reusable storage templates and map fields

Device Configuration now includes Telemetry field templates. Create a template
from observed fields or the HSL preset, edit relative JSON paths, metric keys,
labels, types and units, and select Store and Show by default independently.
Assign it to a source through Assigned template. Save updates every assigned
source; Apply to all <type> sources performs an explicit bulk assignment to
existing owned sources of that type. Future sources can reuse the same template
without redefining fields. Canonical adapter conversions happen before these
paths are evaluated; units in rules describe values, not conversion formulas.

The demo's HSL transport template is assigned to its fifteen HSL sources only.
The two original simulated cars retain their previous collection configuration.
The template stores fourteen available fields. Device Telemetry shows the
typed stored-field table; numerical route context also appears in ordinary
chart discovery. Map widget Appearance contains an independent field selector.
Published dashboard revision 7 displays Speed, Route, Destination, Schedule
offset, Doors open and Acceleration. Storage still includes all fourteen fields.

Real verification on 2026-10-12 at 02:46 Belgrade time disabled acceleration in
the shared template, observed a new live bus reading, and checked its absence
in persisted raw JSON, measurements and position context. The field was then
restored; all fourteen fields resumed. After restarting device-service and
ingestion-service, a cold source cache initially returned 401. Cache-miss
recovery now reads the authenticated internal source registry and validates
the unchanged source key. At 03:06 Belgrade time, real GPS was FRESH with
fourteen stored fields and seven numeric series, while map selection stayed
at six fields. No credential rotation was needed. Evidence (ignored):
`artifacts/telemetry-template-live-summary.json`,
`artifacts/telemetry-template-restart-summary.json`,
`artifacts/telemetry-field-template-settings.jpg`,
`artifacts/map-telemetry-field-settings.jpg`,
`artifacts/configured-telemetry-map.jpg`.

Backend reactor tests passed, followed by focused final registry/ingress checks
and the complete ingestion suite for cache recovery. Frontend typecheck/build
and feature tests passed. The full frontend run passed 304/305 tests; an unrelated
ML workflow timing assertion failed under concurrent backend/Docker load and
passed in the isolated 24-test ML/map rerun. Strict OpenSpec and structure checks
passed. No new workflow commands or AGENTS.md/skill changes are required.


## Boolean telemetry charts

Configured boolean fields are discoverable chart metrics (type boolean, unit boolean, bounds 0/1). Raw JSON retains true/false; measurement series project false to 0 and true to 1. The reserved boolean unit cannot be assigned to numeric fields. Boolean series are excluded from automatic numeric analytics. V014 projects previously stored valid configured boolean observations and legacy accepted transport.doorsOpen positions without changing source times or raw JSON. Rejected observations are excluded. Up to 1000 observations per range are returned as measured states and displayed with step lines, preserving state changes and gaps. Larger ranges use min/max envelopes and do not imply exact transition order. Door chart axes show Closed/Open; other boolean states show False/True, and fractional aggregate statistics show Mixed states.


## Single active bus dashboard (2026-10-12)

The demo owner has an explicit active dashboard quota of 50 (default for other owners remains 10). A new real bus 0020-00004, route 584, was discovered anonymously while the earlier night buses stopped publishing. It uses the shared HSL telemetry template and an explicit connector source binding, bringing the allowlist to 16 vehicles. Dashboard 1dbde519-9644-45de-9633-69e51eb4308e shows one bus map above speed and door-state charts for the rolling last hour. Desktop/tablet charts share a row; mobile stacks them below the map. Map dashboards default to five-second automatic refresh while the tab is visible. Existing fleet dashboard bindings are preserved. Live creation verification observed FRESH positions and all three panels ready.
