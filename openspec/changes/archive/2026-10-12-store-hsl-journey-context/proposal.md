# Store HSL journey context

## Why
The user wants additional real tram observations collected and retained, beyond GPS.

## What Changes
Collect route ID/number, direction, destination, next stop ID, raw schedule offset in seconds, door state, acceleration, planned departure and location source from accepted HSL VP observations. Add a bounded optional transport context in telemetry metrics, preserve it in raw accepted readings and GPS history, expose it in existing latest/history APIs and selected vehicle details.

## Impact
Connector, common event context validator, ingress validation, telemetry normalization/projection, one nullable column migration and frontend details. Numeric metric catalog and analytics event payload remain unchanged. No fabricated backfill or separate stop-name lookup.
