# Simulator Frontend Flow

Creating a simulated device:

1. `POST /devices`
2. `POST /devices/{deviceId}/data-sources` with `sourceType=SIMULATOR`, `protocol=REST`
3. `POST /devices/{deviceId}/data-sources/{dataSourceId}/token`
4. `PUT /simulation/devices/{deviceId}/config`
5. Redirect to `/devices/{deviceId}?tab=simulation`

The Simulation tab lets the user validate YAML, save config, start and stop a run, and inspect run history.

## Automobile flow

Choose Automobile and simulation in the existing wizard. Defaults are VEHICLE_GPS,
CITY_LOOP and HTTP; checkpoints/credential confirmation remain. Adjust
interval/duration/physical speed in Demo Tools and explicitly Save/Start. Power
cycle/anomaly controls are hidden for GPS. Movement tab selects one SIMULATOR/REST
source (automatic only for a single source), polls5s while visible and offers
15m/1h/24h trails. Gaps>30s and impossible jumps break the observed line.
The map shows speed/course/time/freshness; manual pan disables Follow.
Map/tiles/WebGL failure retains textual coordinates/time/speed and Retry.
VITE_MAP_STYLE_URL defaults to OpenFreeMap Bright; internet/WebGL are required
for basemap and attribution is retained. No vehicle IDs/names or full GPS track
are sent to the basemap provider; tile requests reveal the viewed geographic area.

## Vehicle monitoring

Vehicles is a separate fleet console. Demo Tools exposes waypoint/stops and optional vehicle metrics as local drafts with explicit validation/save/restart. [Fleet, history/playback and zones](../flows/vehicle-monitoring-verification.md).
