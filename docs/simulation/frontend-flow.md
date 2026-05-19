# Simulator Frontend Flow

Creating a simulated device:

1. `POST /devices`
2. `POST /devices/{deviceId}/data-sources` with `sourceType=SIMULATOR`, `protocol=REST`
3. `POST /devices/{deviceId}/data-sources/{dataSourceId}/token`
4. `PUT /simulation/devices/{deviceId}/config`
5. Redirect to `/devices/{deviceId}?tab=simulation`

The Simulation tab lets the user validate YAML, save config, start and stop a run, and inspect run history.
