# Active bus dashboard

## Why
The user needs a dashboard with one live bus map and speed/door state charts, and has reached the fixed ten-dashboard quota.

## What Changes
- Support bounded owner-specific active dashboard quota overrides, retaining the default ten.
- Accept safe dotted configured metrics in dashboard queries and discover their actual owned storage units.
- Render stored boolean state series on dashboards with bounded measured points or explicit envelopes.
- Increase the demo owner quota to 50 and publish a map above two charts for a currently active HSL bus.

## Impact
Dashboard storage, query contract, telemetry read projection, frontend chart rendering, local demo setup.
