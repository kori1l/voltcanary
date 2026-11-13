# Design

## Boundaries

Retain VEHICLE/SIMULATOR/REST and VEHICLE_GPS. Custom route is saved in the existing simulation YAML/config revision; never mutate a running config. The run snapshots config and starts at origin. `gps.waypoints` is an optional list of 2–100 `{latitude,longitude,stopSeconds}` points; coordinates finite/in range, stopSeconds integer 0–3600, nonzero closed length, last→first closes automatically. Absence retains CITY_LOOP. Optional `gps.vehicleMetrics: true` generates ignition (0/1), fuelPercent (0–100), odometerKm (nonnegative); initialFuelPercent default80, consumptionLitresPer100Km default8, fuelTankLitres default50, initialOdometerKm default0. Fuel is synthetic consumption, explicitly labeled; no inferred physical diagnostics. Scheduled stops emit speed0 and ignition0. Initial odometer is configuration, current odometer is run-relative and resets on restart; UI states this limit.

GPS validation accepts the original four metrics or the seven metrics when vehicleMetrics enabled. Non-GPS profiles cannot acquire gps fields. Vehicle readings traverse existing HTTP ingestion/Kafka acceptance. Position projection optionally stores the three accepted vehicle fields atomically with coordinates, preserving original DTO fields.

## Reads and access

GET `/telemetry/devices/{deviceId}/positions/recordings?sourceId&from&to&cursor&limit`: `(from,to]`, ascending timestamp cursor exclusive, default1000/max2000, max31 days; return points, nextCursor, hasMore. Snapshot `to` never moves while paging. Cursor validated inside range. Frontend maximum20000 samples per explicit historical load, shows truncation and range advice. No fabricated interpolated GPS for playback; seek selects recorded samples. The existing latest/history endpoints retain their limits and dashboard contract.

POST `/telemetry/vehicles/positions` body list of max100 `{deviceId,sourceId}` targets and optional asOf. Authorize every device/source before returning any positions (fail closed). Database latest reads are bounded by100; frontend fleet poll uses one endpoint, no per-device GPS requests. Device/source discovery uses existing APIs, cached for the mounted console. Existing gateway strips identity headers; telemetry authorization checks current ownership, not arbitrary caller IDs. All mutation endpoints require authenticated user and current owner access (not a dashboard read grant).

GET/PUT `/telemetry/devices/{deviceId}/vehicle-monitoring` stores owner-specific speedLimitKmh (1–300, default80), integer idleMinutes (1–60, default2), up to50 circular zones with UUID/name/lat/lon/radiusMetres (10–50000). Portable new migration; user/device row keyed by current user and authorized device. PUT rejects fractional idle minutes, unknown JSON fields and invalid bounds with400 without mutation, and replaces valid bounded settings atomically. A read-only shared device cannot mutate owner settings.

## Journeys and events

Derived in the frontend from accepted samples only, independently testable pure functions. Break coverage on gaps >60s, impossible movement >300km/h or source switch. A trip starts at speed>3km/h (or geometry if speed absent) and ends at the first sustained idle period >=configured threshold; short stops stay in the same trip. Gaps split trips and expose incomplete flags. Unknown speed/ignition/fuel remains unknown, zero remains zero. Distance is the sum of plausible observed haversine segments, never advertised as certified odometer distance. Report first/last recorded time, moving/elapsed duration, observed distance, max/average speed. Playback reuses VehicleMap and trails stop at the selected sample. CSV escapes strings safely and includes UTC timestamps, coordinates, speed, course, optional vehicle fields. No server command or credential exported.

Circular zone transitions require a known prior state on contiguous observations; first sample establishes state and generates no invented entry. Events include ENTER/EXIT and OVERSPEED transition, deduplicated within contiguous segments; gaps clear transition state and disclose uncertain coverage. Events are an evidence view for the selected history and current saved settings, not durable notification delivery or retroactively authoritative alerts. This distinction appears in the UI and docs.

## UI

Dedicated /vehicles route/sidebar. Fleet map component contains no API calls and retains its map instance across polling; fit/follow selection, marker click and list selection synchronize. 5s visible polling, single flight, abort on unmount/hide/identity change. Rows expose latest status and timestamp. Selected vehicle provides Live / History / Zones tabs and link to Demo Tools. User explicitly loads historical range, chooses playback speed1/5/20, plays/pauses/seeks and selects a trip. Settings save is explicit. Geo-zone circle overlays are cosmetic; zone evaluation uses distances, not map pixels. Loading errors retain previous data labeled stale; failed discovery/binding does not show another vehicle's samples.

## Verification

Tests: original YAML compatibility; custom closed geometry/stops/metrics and validation; projection migration + values; cursor boundaries/ownership/batch limits; trip/zone/overspeed gap rules, export and zero/null; frontend typecheck/test/build; real stack custom route and fleet/history/settings/playback. Update current docs and active change indexes. Archive only after complete gates, record remaining unverified cases accurately.
