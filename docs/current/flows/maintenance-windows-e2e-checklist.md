# Maintenance Windows E2E Checklist

Use this checklist after deploying device-service, analytics-service,
alert-service, api-gateway, frontend, and Redpanda.

## Setup

1. Create or select an active device.
2. Start a simulator or send telemetry so charts, analytics, and alerts have
   current data.
3. Confirm `maintenance-window.changed.v1` exists in Redpanda.
4. Confirm analytics-service and alert-service can call device-service with
   `X-Internal-Service-Token`.

## Create And Project

1. Open the device detail page.
2. Open the Maintenance tab.
3. Create a window that starts now and ends in one hour with all policy flags
   enabled.
4. Verify the window appears as `ACTIVE`.
5. Verify the device header shows `Maintenance active`.
6. Verify Telemetry, Analytics, and Alerts tabs show the maintenance notice.
7. Verify analytics chart overlays the maintenance interval.

## Analytics Behavior

1. Trigger telemetry during the active window.
2. Confirm analytics-service does not enqueue a normal telemetry-triggered job
   for excluded samples.
3. Run manual recalculation.
4. If the selected range is fully covered by maintenance, confirm the job is
   `SKIPPED` with `skipReason=MAINTENANCE_FULL_COVERAGE`.
5. If the selected range is partially covered, confirm feature metadata includes
   excluded sample and maintenance window information.
6. Confirm ml-service receives `excludedIntervals` for windows with
   `excludeFromMl=true`.

## Alert Behavior

1. Emit or replay an anomaly/risk event overlapping the active window.
2. Confirm no new alert appears for the event.
3. Confirm alert-service records a row in `alert_suppressions`.
4. Create an alert before a future maintenance window.
5. Move or create the maintenance window so it overlaps the alert calculation
   window.
6. Confirm the existing unresolved alert is auto-resolved with the maintenance
   reason.
7. Confirm `alert_events` contains `AUTO_RESOLVED_BY_MAINTENANCE`.

## Reconciliation

1. Stop analytics-service and alert-service consumers.
2. Create or update a maintenance window.
3. Start the services again.
4. Confirm reconciliation fetches changes from
   `/internal/maintenance-windows/changes`.
5. Confirm both local read models converge to the latest version.

## Finish And Cancel

1. Use `Finish now` for an active window.
2. Confirm status changes to `FINISHED`.
3. Create another scheduled window.
4. Cancel it from the UI.
5. Confirm status changes to `CANCELLED` and downstream projections update.
