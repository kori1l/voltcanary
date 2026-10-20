# Connect real Helsinki vehicles

## Why
The user wants actual public transport as owned VEHICLE devices, not simulated movement. HSL's public HFP MQTT stream supplies observed positions without an API key.

## What Changes
- Add an optional, independently restartable HSL MQTT-to-REST connector in deployment tooling.
- Provision ten currently active trams under the requested demo owner and publish a Helsinki HSL dashboard with one common vehicle-map widget.
- Preserve vehicle identity across route changes, source timestamps and nullable optional metrics. Reuse ordinary source authentication and accepted telemetry projection.
- Provide bounded sampling, validation, reconnection, operational status and focused tests.

## Impact
New capability: `hsl-live-vehicles`. Existing Java APIs and event schemas remain compatible. This is a local Compose integration, not a general public-feed management UI or fleet auto-discovery service. No simulator is involved in HSL devices.
