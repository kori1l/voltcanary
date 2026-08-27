# VoltCanary Agent E2E Verification Runbook

This file is the canonical checklist for manual or agent-driven platform verification. Use it as a step-by-step runbook, not as guidance. A scenario is passed only when every assertion in that scenario is verified.

## Execution Rules

- Run scenarios through the public gateway and frontend whenever possible.
- Do not treat `/actuator/health` as functional coverage.
- Use a fresh test user per run unless the scenario explicitly requires existing state.
- Prefer frontend flows for user-visible behavior, then verify with API and database state.
- For simulator YAML, fetch templates from `/simulation/templates/{profile}`. Do not hand-write YAML except for YAML validation negative tests.
- Every scenario must record evidence: user email, ids, timestamps, API response counts, UI visibility, and log/outbox checks.
- Any unexpected frontend `pageerror` fails the run.
- Any unexpected browser `requestfailed` fails the run, except documented optional-data 404s for brand-new devices.
- Any backend `ERROR`, `Exception`, `APPLICATION FAILED`, stuck `PENDING` outbox row, or failed event replay assertion must be investigated before marking the run passed.

## Standard Environment Setup

1. Start stack
- Command: `docker compose --profile app up -d`.
- Wait until services are stable.
- Required healthy/reachable services: gateway `8080`, identity `8081`, device `8082`, ingestion `8083`, telemetry `8084`, analytics `8085`, alert `8086`, simulator `8087`, ai-agent `8088`, ml-service `8090`, frontend `3000`, postgres, timescaledb, redis, redpanda, minio.

2. Verify health endpoints
- `GET http://localhost:8080/actuator/health`
- `GET http://localhost:8081/actuator/health`
- `GET http://localhost:8082/actuator/health`
- `GET http://localhost:8083/actuator/health`
- `GET http://localhost:8084/actuator/health`
- `GET http://localhost:8085/actuator/health`
- `GET http://localhost:8086/actuator/health`
- `GET http://localhost:8087/actuator/health`
- `GET http://localhost:8088/actuator/health`
- `GET http://localhost:8090/health`
- `GET http://localhost:3000/index.html`
- Assertion: each endpoint is HTTP 200 and reports healthy/ok where applicable.

3. Browser setup
- Use Playwright or equivalent browser automation.
- If bundled browsers are unavailable, use installed Chrome at `C:/Program Files/Google/Chrome/Application/chrome.exe`.
- Collect console errors, page errors, and request failures for the whole run.

4. Test data naming
- Email format: `agent-e2e-{timestamp}@example.com`.
- Device name format: `Agent E2E Fridge {timestamp}`.
- Source external id format: `agent-e2e-src-{timestamp}`.
- Webhook channel name format: `Agent E2E Webhook {timestamp}`.

## Scenario 01: Auth, Session, And Routing

Steps:
- Open `/register` in the frontend.
- Register a new user.
- Assert redirect to `/devices` or another authenticated page.
- Call `GET /auth/me` through gateway with the access token.
- Reload the page and confirm the session remains valid.
- Logout through UI or `POST /auth/logout`.
- Confirm protected pages redirect to login or reject access.
- Login again with the same credentials.
- Navigate to an unknown authenticated route.

Assertions:
- Registration returns access and refresh tokens.
- `/auth/me` has expected email, role, and active status.
- Login succeeds after logout.
- Protected route without token is rejected.
- Unknown authenticated route redirects to a valid app route.
- No token appears in logs, URL, or visible UI text.

Negative checks:
- Wrong password returns `401`.
- Missing token for `/devices` returns `401` through API.
- Spoofed identity headers sent to gateway do not grant access.

## Scenario 02: Password And Email Flows

Steps:
- Open `/forgot-password`.
- Submit the registered email.
- Open `/reset-password` with an invalid token.
- Open `/verify-email` with an invalid token.
- Call email verification request API if exposed in UI/API.

Assertions:
- Forgot password request does not leak whether the account exists.
- Invalid reset token is rejected with a user-visible error.
- Invalid email verification token is rejected with a user-visible error.
- Existing session remains stable after failed reset/verify attempts.

## Scenario 03: Device Inventory CRUD

Steps:
- Create a location from UI or API.
- List locations and assert it appears.
- Patch the location with building/floor/room/tags if UI/API supports it.
- Create a `FRIDGE` device in that location.
- List devices and assert the device appears.
- Open `/devices/{deviceId}`.
- Patch device name, manufacturer, model, and metadata.
- Disable the device.
- Enable the device.
- Export devices CSV.
- Import a small valid devices CSV.
- Import an invalid devices CSV.

Assertions:
- Created and patched values are visible in UI and API.
- Disabled device cannot be used for operations that require active device if platform enforces that.
- Re-enabled device returns to usable state.
- CSV export contains expected headers and at least the created device.
- Valid CSV import reports imported count.
- Invalid CSV import reports validation errors without partial corrupt state.

Negative checks:
- Secret-like metadata is rejected.
- Invalid device type is rejected.
- Unauthorized user cannot read another user's device.

## Scenario 04: Data Sources And Source Tokens

Steps:
- Create a data source using frontend contract: `POST /devices/{deviceId}/data-sources` with `sourceType`, `protocol`, and `externalId`.
- List data sources and assert it appears.
- Get data source by id.
- Patch source type/protocol/external id if supported.
- Create/rotate token through `POST /devices/{deviceId}/data-sources/{dataSourceId}/token`.
- Revoke token.
- Create a new token again for later ingestion scenarios.
- Export data sources CSV.
- Import valid and invalid data source CSVs.

Assertions:
- Token value is returned only at creation/rotation time.
- Token prefix is stored/visible only as a prefix, not full token.
- Old token fails after revoke or rotation.
- New token can ingest telemetry.
- Source external id must match submitted telemetry `sourceId`.

Negative checks:
- Wrong source token returns `401` or `403`.
- Token for source A cannot ingest telemetry for source B.
- Deleted/disabled source cannot ingest.

## Scenario 05: Sensors And Analytics Config

Steps:
- Create sensors for `activePowerW`, `voltageV`, `currentA`, and `energyWh`.
- List sensors and open each sensor by id.
- Patch sensor name, unit, metric mapping, metadata.
- Delete one optional sensor.
- Get analytics config for the device.
- Patch analytics config with a valid JSON config.
- Patch analytics config with invalid JSON/invalid values.

Assertions:
- Sensor CRUD is reflected in UI and API.
- Metric mapping names match telemetry metric names used by charts.
- Analytics config patch persists and survives reload.
- Invalid analytics config is rejected without overwriting the previous valid config.

## Scenario 06: Direct REST Ingestion

Steps:
- Use a valid source token.
- Send one reading to `POST /ingestion/readings` with `sourceId`, current timestamp, and metrics.
- Send a batch to `POST /ingestion/readings/batch`.
- Send a historical import to `/ingestion/imports/readings` if needed.
- Poll `/telemetry/devices/{deviceId}/latest`.
- Query `/telemetry/devices/{deviceId}/readings` for each numeric metric.
- Query `/telemetry/devices/{deviceId}/metrics` and `/quality`.

Assertions:
- Ingestion returns accepted response with an event id or accepted count.
- Latest telemetry timestamp is within the expected time window.
- Chart endpoints return the ingested points.
- Quality endpoint reports usable data.
- Duplicate reading with same idempotency characteristics is ignored or deduplicated as designed.

Negative checks:
- Timestamp too old/future is rejected except import endpoint where allowed.
- Empty metrics are rejected.
- Non-numeric metric values do not break numeric chart APIs.
- Wrong token/source mismatch is rejected.

## Scenario 07: MQTT Ingestion

Steps:
- Ensure Mosquitto is running.
- Publish telemetry to topic `voltcanary/sources/{sourceId}/telemetry` using the expected JSON payload.
- Poll telemetry latest/readings.

Assertions:
- MQTT payload is consumed by ingestion-service.
- Telemetry latest/readings reflect the MQTT payload.
- Bad topic or bad payload is rejected/logged without crashing ingestion-service.

Skip rule:
- If MQTT listener is disabled by environment config, record as skipped with config evidence.

## Scenario 08: Simulator Config, Runs, And Telemetry Freshness

Steps:
- Fetch YAML from `/simulation/templates/FRIDGE?sourceId={externalId}`.
- Save config with `PUT /simulation/devices/{deviceId}/config` and valid source token.
- Validate config with `/simulation/devices/{deviceId}/config/validate`.
- Open `/devices/{deviceId}?tab=simulation`.
- Start simulator.
- Wait at least 15 seconds.
- Query `/simulation/devices/{deviceId}/status`.
- Query `/simulation/devices/{deviceId}/runs`.
- Query `/telemetry/devices/{deviceId}/latest`.
- Query `/telemetry/devices/{deviceId}/readings?metric=activePowerW&from={now-10m}&to={now}`.
- In the UI, switch from the simulation tab to `overview`, `telemetry`, `registry`, `analytics`, and `alerts`, then return to `simulation`.
- Stop simulator.
- Start it again and verify a new run or resumed behavior as designed.

Assertions:
- Config status is valid.
- Start changes run status to `RUNNING`.
- After switching to other device tabs and returning to `simulation`, the UI still shows `RUNNING`, the primary action is `Stop`, and generated points are not reset to zero.
- `generatedPoints > 0` after 15 seconds.
- `lastError == null`.
- Latest telemetry timestamp is within the last 2 minutes.
- Active power chart has at least one point in the last 10 minutes.
- Stop changes status to `STOPPED` and stops point growth.
- Run history shows the run and events.

Negative checks:
- Invalid YAML shows validation errors and cannot start.
- Missing source token for HTTP config is rejected.
- Starting an already running simulator returns conflict.
- A failed run is a test failure unless the scenario intentionally caused it.
- If changing device tabs makes a running simulator look stopped or changes the primary action back to `Start`, fail the run.

## Scenario 09: Telemetry UI Charts

Steps:
- Open `/devices/{deviceId}?tab=telemetry` after simulator or direct ingestion generated recent data.
- Test ranges: `15m`, `1h`, `6h`, `24h`, `7d`, and custom.
- Select and deselect metric checkboxes.
- Open metric detail panel.
- Toggle point visibility.
- Use range selection in chart detail if supported.

Assertions:
- For recent simulator data, `activePowerW` does not show `No samples in selected range` in `15m` and `1h` ranges.
- Chart labels and tooltip render without JS errors.
- Metric detail shows sample count greater than zero for active metric.
- Custom range around latest timestamp shows data.
- Custom range far away from data intentionally shows no data and does not crash.

Negative checks:
- Empty telemetry device shows clear empty state, not a broken chart.
- Very dense data is downsampled/rendered without browser errors.

## Scenario 10: Telemetry Export And Bounds

Steps:
- Export readings with `/telemetry/devices/{deviceId}/readings/export` for a valid range.
- Request readings with missing metric, invalid metric, invalid range, `from > to`, and too-wide range.

Assertions:
- Valid export returns JSON with `deviceId`, `metric`, `points`, and `nextCursor`; `points` contains rows for the selected range.
- Invalid range returns validation error.
- Too-wide range is rejected or capped according to service config.
- Invalid metric does not crash telemetry-service.

## Scenario 11: Analytics Recalculation And Results

Steps:
- Ensure device has at least 5 telemetry points.
- Call `POST /analytics/devices/{deviceId}/recalculate`.
- Poll `/analytics/devices/{deviceId}/jobs/{jobId}` until terminal state.
- Query `/baseline`, `/features`, `/anomalies`, `/risk`, `/recommendations`, `/predictions/latest`, and `/jobs`.
- Open `/devices/{deviceId}?tab=analytics`.

Assertions:
- Job reaches `COMPLETED`.
- Baseline has sample count greater than zero.
- Features return values for expected metrics.
- Risk endpoint returns current risk level/score.
- Recommendations render in UI.
- Prediction endpoint returns either a valid prediction or a documented unavailable state.
- Analytics tab does not show stale job state after completion.

Negative checks:
- Recalculate on a device with no telemetry returns clear no-data/failed state, not service crash.
- Duplicate recalculation with same input hash does not create uncontrolled duplicate work.

## Scenario 12: Analytics Event Pipeline

Steps:
- After telemetry ingestion or simulator run, wait for telemetry outbox publish.
- Check telemetry DB `telemetry_event_outbox` status counts.
- Check analytics DB `processed_events` for `telemetry.validated.v1`.
- Check analytics jobs created from telemetry events.
- Replay a processed `telemetry.validated.v1` event with same `eventId`.

Assertions:
- No telemetry outbox rows remain `PENDING` after retry window.
- Analytics processed event count increases for new events.
- Replayed event does not increase processed count or jobs count.
- Analytics jobs have deterministic `window_start`, `window_end`, and non-empty `input_hash`.
- No locked jobs remain after completion.

## Scenario 13: Alerts Creation, Listing, And Summary

Steps:
- Generate alerts through analytics if possible, or create a controlled DB fixture and use only public API/UI for actions after fixture setup.
- Query `/alerts`, `/alerts/page`, `/alerts/summary`, and `/alerts/{id}`.
- Filter by device, status, severity, type, from, and to.
- Open `/alerts` UI.
- Open `/devices/{deviceId}?tab=alerts`.

Assertions:
- Alerts list and summary counts match status/severity filters.
- Pagination returns stable content and total counts.
- Alert trace fields render: source event, anomaly/risk id, rule id, calculation window, input hash where present.
- Device-specific alerts tab shows only alerts for that device.

Negative checks:
- User cannot read alerts for another user's device.
- Invalid alert id returns `404`.
- Invalid filter values return validation errors.

## Scenario 14: Alert Acknowledge And Resolve Visibility

Steps:
- Start with an `OPEN` alert.
- Open `/alerts` UI.
- Assert status filter default is `all`.
- Acknowledge the alert.
- Resolve the alert with a non-empty reason.
- Reload `/alerts`.
- Open `/devices/{deviceId}?tab=alerts`.
- Query `/alerts?status=RESOLVED`.

Assertions:
- Status transition is `OPEN -> ACKNOWLEDGED -> RESOLVED`.
- Resolved alert remains visible on default `/alerts`.
- Resolution reason is visible.
- Resolved alert remains visible on default device alerts tab.
- Resolved alert is absent from `OPEN` and `ACKNOWLEDGED` filters.
- Resolved alert is present in `RESOLVED` filter.

Failure guard:
- If the alert disappears from default UI immediately after resolve, fail the run.

## Scenario 15: Notification Channels, Attempts, And Preferences

Steps:
- Open `/alerts` UI.
- Create webhook notification channel.
- List notification channels.
- Disable the channel.
- Enable the channel.
- Update the channel if API/UI supports it.
- Delete the channel.
- Get notification preferences.
- Update notification preferences.
- Trigger or inspect notification attempts for an alert.

Assertions:
- Channel create/disable/enable/delete is visible in UI and API.
- Deleted channel disappears and cannot be used.
- Preferences persist after reload.
- Notification attempts show status and provider response/error where applicable.

Negative checks:
- Invalid webhook URL is rejected.
- Unsupported channel type is rejected.
- Disabled channel does not send new attempts.

## Scenario 16: Dashboard Overview

Steps:
- Open `/dashboard` with existing devices, telemetry, analytics, and alerts.
- Compare displayed counts with API data for devices and alerts.
- Navigate from dashboard cards/links to devices and alerts.

Assertions:
- Device counts match API list.
- Active/resolved alert counts match alerts API after current filters.
- Latest activity reflects recent telemetry or alert activity.
- Navigation links route to correct pages.

## Scenario 17: AI Agent Plans And Provider Settings

Steps:
- Open `/ai-agent`.
- Query `/ai-agent/tools` and `/ai-agent/provider`.
- Create a plan with a safe prompt that uses read-only platform tools.
- Execute the plan if provider/config permits.
- Open AI agent settings page.
- List model provider capabilities and model providers.
- Create a test provider with non-production credentials/placeholder if validation allows.
- Rotate secret, set default, add model config, update model config, delete model config/provider.

Assertions:
- Tools list renders in UI.
- Provider state is visible and does not leak secrets.
- Plan creation returns structured steps/actions.
- Execution result reaches terminal status or documented provider-unavailable state.
- Secret rotation never returns full secret after initial submission.
- Delete removes provider/model from list.

Negative checks:
- Invalid provider config is rejected.
- User without required role cannot manage global provider settings if role restriction exists.
- Provider test failure is surfaced without crashing UI.

## Scenario 18: Account Page And Admin User Management

Steps:
- Open `/account` as normal user.
- Verify profile, role, status, account, and device account sections.
- Create or promote an admin user via controlled setup.
- As admin, list users.
- Change a user's status: `ACTIVE`, `DISABLED`, `LOCKED`, `PENDING_VERIFICATION` where allowed.
- Change role where allowed.
- List sessions for a user.
- Revoke sessions.
- Delete a test user.

Assertions:
- Normal user cannot access admin-only routes.
- Admin can list users and update status/role.
- Disabled/locked user cannot login.
- Revoked sessions stop working.
- Deleted user cannot login or refresh.

Negative checks:
- User cannot promote self unless explicitly allowed.
- Invalid status/role is rejected.

## Scenario 19: Account Lifecycle Eventual Consistency

Steps:
- Create victim user with account, device, data source, source token, and at least one telemetry point.
- Delete or disable victim through admin flow.
- Poll identity outbox.
- Poll device-service lifecycle projection endpoint if available internally.
- Attempt victim login, refresh, API access, and source-token ingestion.
- Replay the same lifecycle event id.

Assertions:
- Identity outbox publishes lifecycle event.
- Device-service consumes lifecycle event exactly once.
- Victim login fails.
- Victim refresh fails.
- Old access token is rejected by protected APIs.
- Old source token ingestion fails.
- Replay does not increase processed count or duplicate side effects.

## Scenario 20: Gateway Security And Rate Limiting

Steps:
- Send requests with spoofed `X-User-*` headers and no token.
- Send requests with valid token and spoofed conflicting headers.
- Hit admin endpoints as normal user.
- Exercise rate-limited endpoints with a burst if safe.

Assertions:
- Gateway strips or overrides spoofed identity headers.
- Normal user gets `403` on admin endpoints.
- Missing token gets `401` on protected endpoints.
- Rate limit returns expected response when threshold is exceeded.
- Gateway logs do not expose secrets.

## Scenario 21: Event Outbox, Inbox, And Replay Matrix

Check these topics/events when relevant changes are made:

- `identity.account-lifecycle.v1`
- `device.registry-events` or device outbox topics used by the service
- `telemetry.raw.v1`
- `telemetry.validated.v1`
- `anomaly.detected.v1`
- `risk-score.changed.v1`

Steps:
- Capture outbox status counts before scenario.
- Run scenario that emits events.
- Wait for outbox rows to become `PUBLISHED`.
- Capture processed event counts in consuming service.
- Replay one event with same `eventId`.
- Capture processed event counts and domain side effects again.

Assertions:
- New events are eventually published.
- Consumers eventually process new event ids.
- Replayed events do not duplicate domain rows, alerts, jobs, or projections.
- No rows remain locked beyond lease timeout.
- Failed rows have bounded retry behavior and clear error messages.

## Scenario 22: Retention And Cleanup Jobs

Steps:
- Identify retention jobs for alerts, analytics, telemetry, refresh tokens, simulator leases, and notification attempts.
- Seed old data where safe.
- Trigger scheduled job by waiting or direct method/API if exposed only in tests.
- Verify only eligible old data is removed.

Assertions:
- Active data is not deleted.
- Resolved/expired data older than TTL is deleted or archived as designed.
- Cleanup job records metrics/logs.
- Cleanup does not fail when there is nothing to delete.

## Scenario 23: Frontend Navigation And Responsive Smoke

Steps:
- Visit `/login`, `/register`, `/forgot-password`, `/reset-password`, `/verify-email`, `/dashboard`, `/devices`, `/devices/{id}`, `/alerts`, `/ai-agent`, `/ai-agent/settings`, `/account`.
- Test desktop viewport.
- Test mobile viewport.
- Use main navigation and deep links with query tabs.

Assertions:
- No route renders blank page.
- No page has uncaught JS error.
- Main CTA buttons are reachable.
- Forms show validation errors for invalid input.
- Auth redirects preserve sane behavior.

## Scenario 24: Service Resilience Smoke

Steps:
- Restart one stateless service at a time: simulator, telemetry, analytics, alert, device, identity, gateway, frontend.
- After restart, repeat a minimal flow touching that service.
- For event consumers, verify processing resumes.

Assertions:
- Service restarts cleanly.
- No permanent stuck locks remain.
- In-flight or subsequent events are eventually processed.
- UI recovers after refresh.

Caution:
- Do not run destructive database resets unless explicitly requested.

## Scenario 25: Multi-Replica Safety

Steps:
- If Docker compose fixed host ports block `--scale`, create/use an override that removes host port bindings for scaled services or run in Kubernetes.
- Scale event consumers/scheduled workers where supported.
- Run telemetry ingestion, analytics recalculation, alert generation, simulator start, and lifecycle events.

Assertions:
- Outbox leasing prevents duplicate publishes beyond allowed at-least-once semantics.
- Inbox idempotency prevents duplicate side effects.
- Scheduler locks prevent duplicate cleanup/escalation jobs.
- Simulator active-run lease prevents two replicas from running the same device simulation.
- Analytics job leasing prevents duplicate workers from completing the same job twice.

## Scenario 26: API Contract Negative Matrix

For every public endpoint touched in this run, test at least one invalid input:

- Missing auth.
- Wrong role.
- Invalid UUID.
- Unknown resource id.
- Invalid enum.
- Missing required field.
- Oversized string where limits exist.
- Secret-like metadata where blocked.
- Invalid timestamp/range.

Assertions:
- Error response has stable status code and useful message.
- Error response includes correlation id where gateway/service provides it.
- Invalid request does not mutate state.

## Scenario 27: Data Privacy And Secret Handling

Steps:
- Create source token and AI provider secret.
- Reload UI and query list/get APIs.
- Inspect logs around token/secret creation.

Assertions:
- Full source token appears only once at creation/rotation.
- Full source token is not visible in later list/get responses.
- AI provider secret is never returned after submission.
- Logs do not contain full source tokens, JWTs, refresh tokens, or provider secrets.

## Scenario 28: Observability Smoke

Steps:
- Query `/actuator/metrics` for key Spring services if exposed.
- Query Prometheus endpoints if exposed.
- Run a flow that creates telemetry, analytics jobs, alerts, and notifications.
- Check logs for correlation ids.

Assertions:
- Metrics endpoints respond.
- Domain counters/timers move after actions where available.
- Logs include correlation ids for API requests and important async processing.
- Logs avoid secrets and excessive payload dumps.

## Standard Database Checks

Use these after scenarios that touch event pipelines:

- Identity DB: `identity_event_outbox` status counts and scheduler locks.
- Device DB: `device_event_outbox`, `processed_events`, account lifecycle projection.
- Telemetry DB: `telemetry_event_outbox`, `processed_events`, latest `telemetry_readings.recorded_at`.
- Analytics DB: `analytics_event_outbox`, `processed_events`, `analytics_jobs` status, locked jobs, input hashes.
- Alert DB: `processed_events`, alert status counts, notification attempt status counts.
- Simulator DB: active `simulation_runs`, lease owner/until, generated points, failed runs and errors.

Failure thresholds:
- Any unexpected `PENDING` outbox rows after retry window fail the run.
- Any lock older than its lease window fails the run.
- Any new unexpected `FAILED` job/run fails the run.
- Any processed-event replay causing duplicate side effect fails the run.

## Standard Log Checks

After each major group, scan logs for:

- `ERROR`
- `Exception`
- `APPLICATION FAILED`
- `telemetryOutboxPublishFailed`
- `analyticsJobFailed`
- `simulator_runs_failed`
- `Unauthorized` bursts not caused by explicit negative tests
- frontend build/runtime errors

Assertion:
- Every matching line is either explained by a negative test or fails the run.

## Final Report Template

Every verification run must end with this report:

```text
Verification date:
Git commit / branch:
Docker compose profile:
Browser engine:

Test identities:
- user email:
- admin email:

Created resources:
- account id:
- location id:
- device id:
- data source id:
- source external id:
- simulator run id:
- alert ids:
- notification channel ids:
- ai provider/model ids:

Scenario results:
- 01 Auth, Session, And Routing: PASS/FAIL/SKIP - evidence
- 02 Password And Email Flows: PASS/FAIL/SKIP - evidence
- 03 Device Inventory CRUD: PASS/FAIL/SKIP - evidence
- 04 Data Sources And Source Tokens: PASS/FAIL/SKIP - evidence
- 05 Sensors And Analytics Config: PASS/FAIL/SKIP - evidence
- 06 Direct REST Ingestion: PASS/FAIL/SKIP - evidence
- 07 MQTT Ingestion: PASS/FAIL/SKIP - evidence
- 08 Simulator Config, Runs, And Telemetry Freshness: PASS/FAIL/SKIP - evidence
- 09 Telemetry UI Charts: PASS/FAIL/SKIP - evidence
- 10 Telemetry Export And Bounds: PASS/FAIL/SKIP - evidence
- 11 Analytics Recalculation And Results: PASS/FAIL/SKIP - evidence
- 12 Analytics Event Pipeline: PASS/FAIL/SKIP - evidence
- 13 Alerts Creation, Listing, And Summary: PASS/FAIL/SKIP - evidence
- 14 Alert Acknowledge And Resolve Visibility: PASS/FAIL/SKIP - evidence
- 15 Notification Channels, Attempts, And Preferences: PASS/FAIL/SKIP - evidence
- 16 Dashboard Overview: PASS/FAIL/SKIP - evidence
- 17 AI Agent Plans And Provider Settings: PASS/FAIL/SKIP - evidence
- 18 Account Page And Admin User Management: PASS/FAIL/SKIP - evidence
- 19 Account Lifecycle Eventual Consistency: PASS/FAIL/SKIP - evidence
- 20 Gateway Security And Rate Limiting: PASS/FAIL/SKIP - evidence
- 21 Event Outbox, Inbox, And Replay Matrix: PASS/FAIL/SKIP - evidence
- 22 Retention And Cleanup Jobs: PASS/FAIL/SKIP - evidence
- 23 Frontend Navigation And Responsive Smoke: PASS/FAIL/SKIP - evidence
- 24 Service Resilience Smoke: PASS/FAIL/SKIP - evidence
- 25 Multi-Replica Safety: PASS/FAIL/SKIP - evidence
- 26 API Contract Negative Matrix: PASS/FAIL/SKIP - evidence
- 27 Data Privacy And Secret Handling: PASS/FAIL/SKIP - evidence
- 28 Observability Smoke: PASS/FAIL/SKIP - evidence

Outbox/inbox summary:
- identity:
- device:
- telemetry:
- analytics:
- alert:

Frontend errors:
- pageerror count:
- requestfailed count:
- console error count:

Backend log findings:
- unexplained errors:
- explained negative-test errors:

Known skips and reason:
- scenario:
- reason:

Final verdict: PASS/FAIL
Blocking defects:
- defect:
- reproduction:
- expected:
- actual:
```

## Minimal Must-Pass Gate

If time is limited, these scenarios are still mandatory and cannot be skipped:

- Scenario 01 Auth, Session, And Routing.
- Scenario 03 Device Inventory CRUD.
- Scenario 04 Data Sources And Source Tokens.
- Scenario 06 Direct REST Ingestion.
- Scenario 08 Simulator Config, Runs, And Telemetry Freshness.
- Scenario 09 Telemetry UI Charts.
- Scenario 11 Analytics Recalculation And Results.
- Scenario 14 Alert Acknowledge And Resolve Visibility.
- Scenario 15 Notification Channels, Attempts, And Preferences.
- Scenario 19 Account Lifecycle Eventual Consistency.
- Scenario 21 Event Outbox, Inbox, And Replay Matrix.
- Scenario 23 Frontend Navigation And Responsive Smoke.
- Scenario 27 Data Privacy And Secret Handling.
