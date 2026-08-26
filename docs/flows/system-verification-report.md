# System Verification Report

Date: 2026-06-06
Environment: local Docker Compose app profile, frontend `http://localhost:3000`, API Gateway `http://localhost:8080`.

Source checklist: `docs/flows/agent-e2e-verification-instructions.md`.

## Result

Automated gateway-level smoke/e2e verification passed after one analytics defect was fixed.

Verified through live API Gateway:

- Service health for gateway, identity, device, ingestion, telemetry, analytics, alert, simulator, ai-agent, and ml-service.
- Protected route without token returns `401`.
- Register, login, `/auth/me`, public refresh.
- Gateway ignores spoofed `X-User-*` headers.
- Regular `USER` receives `403` on admin route.
- Current account lazy creation.
- Location create/list search.
- Device create/list/get.
- Device secret-like metadata rejection.
- Data source create and source token rotation.
- Sensor create/list.
- Analytics config get/patch.
- Simulation template, validate, save, start, status, stop.
- Telemetry latest/readings/metrics/quality after simulator-generated points.
- Empty telemetry range returns empty points and does not fail.
- Analytics baseline/features/anomalies/risk/recommendations/prediction endpoints.
- Analytics recalculate/jobs endpoint.
- Alerts summary/list static routes.
- Notification channels list, secret-like config rejection, create/disable/enable/delete.
- Notification preferences get/put.
- AI Agent provider/tools.
- ML health.
- Destructive device deletion lifecycle.
- Destructive user disable/delete lifecycle.
- Old source tokens are rejected after device decommission and after user deletion with an active device.

## Fixed During Verification

### Analytics ignored fresh telemetry in current minute

Symptom:

- Simulator generated telemetry successfully.
- `/telemetry/devices/{deviceId}/readings` returned fresh points.
- `/analytics/devices/{deviceId}/recalculate` completed, but `/analytics/devices/{deviceId}/baseline` returned `sampleCount=0`.

Root cause:

- `AnalyticsRecalculationService` used `Instant.now().truncatedTo(ChronoUnit.MINUTES)` as `windowEnd`.
- Fresh telemetry in the current minute was excluded from the analytics query window.

Fix:

- `windowEnd` now uses the actual current `Instant`.
- Added a regression assertion that the telemetry query window end is not earlier than the recalculate request start.

Verified after restart:

- New simulator run generated 3 points.
- Telemetry readings returned those 3 points.
- Analytics baseline returned `sampleCount=3` and correct min/max/mean values.

### Deleted users with active devices could keep ingesting through old source tokens

Symptom:

- User deletion correctly blocked login, refresh, and `/accounts/current`.
- However, an active device owned by a deleted account could still pass the device-service internal data-source existence check.
- Because ingestion cached source registry data by token prefix, an old source token could continue to submit readings until the source/device itself was decommissioned.

Root cause:

- `DataSourceService.exists(deviceId, sourceId)` checked device status and source status, but did not check the owning account lifecycle status.

Fix:

- Internal data-source existence now also requires the owning account to be `ACTIVE`.
- Ingestion revalidates cached source tokens against device-service and evicts the token-prefix cache entry when the device/source is no longer valid.

Verified after restart:

- `DELETE /devices/{id}` archives the device as `DECOMMISSIONED`.
- Decommissioned devices cannot be enabled again.
- Internal source exists returns `false` after device deletion.
- Ingestion with the old source token returns `404` after device deletion.
- Admin `PATCH /auth/admin/users/{id}/status` to `DISABLED` blocks login, refresh, and `/accounts/current` with existing access token.
- Admin `DELETE /auth/admin/users/{id}` returns user status `DELETED`, revokes refresh sessions, blocks login, and syncs device account lifecycle.
- User deletion with an otherwise active device makes internal source exists return `false`.
- Ingestion with the old source token returns `404` after user deletion with an active device.

## Not Fully Automated In This Pass

These scenarios remain in the manual checklist because they require browser console inspection, provider mocks, admin fixtures, destructive dependency shutdown, or long-running time-based behavior:

- Browser console checks for React warnings and layout breakage.
- Drag-select chart behavior in the actual browser UI.
- Full cross-account isolation using two browser sessions.
- Admin status/role/session management with a seeded admin user.
- Hard physical cleanup/cascade policy for account-owned rows after user deletion.
- Password reset and email verification token delivery semantics.
- CSV import all-or-nothing cases.
- Backfill/import ingestion semantics.
- MQTT ingestion path.
- Alert creation from deliberate power spike/growth profile and subsequent notification attempts.
- Escalation thresholds for WARNING/HIGH/CRITICAL alerts.
- Provider timeout/retry behavior for external notification channels.
- Downstream failure scenarios by stopping device-service, telemetry-service, alert-service, ml-service, PostgreSQL, TimescaleDB, Redpanda, and Redis.
- DLQ/poison-message handling.
- Retention cleanup with shortened TTLs.

## Current Follow-Up

No broken scenario from the automated pass remains open after the analytics and lifecycle fixes.

Recommended next verification step:

1. Run the browser checks from `agent-e2e-verification-instructions.md`, especially chart range/no-data behavior and alert-generating simulator profiles.
2. Add a reusable committed smoke script later if this flow should become part of CI or pre-demo verification.
3. Decide whether deleted accounts should physically decommission devices/sources or retain rows only for audit/history. Runtime access and ingestion are already blocked.

## Browser UI Verification Addendum

Date: 2026-06-06
Tooling: Playwright Chromium headless inside the `frontend` Docker container. Browser requests to `http://localhost:8080` were rewritten to `http://api-gateway:8080` because the browser ran inside the container network.

Verified UI checkpoints:

- `/dashboard` loads for an authenticated user.
- `/devices` loads and shows a prepared simulator device.
- `/devices/{deviceId}?tab=telemetry` loads telemetry monitoring and active power data.
- `/devices/{deviceId}?tab=simulation` loads simulation controls and generated point status.
- Empty/small telemetry chart range does not crash the page.
- `/alerts` loads summary, static alert routes, and notification channel section.
- Webhook notification channel can be created through UI.
- Hard refresh on protected device details route keeps auth state.
- Unknown authenticated route redirects to `/devices`.

Browser console result:

- No React duplicate key warnings.
- No React Router future flag warnings.
- No uncaught `pageerror` events.
- No chart crash messages.

Notes:

- One `requestfailed` entry with `net::ERR_ABORTED` was observed after clicking the channel delete action in the browser smoke. The UI checkpoint itself passed and no page/runtime error was produced. This looks like a browser-side aborted request during the smoke interaction rather than the previous alert route bug. API-level channel create/disable/enable/delete was already verified successfully through the gateway.
- Destructive dependency shutdown scenarios remain intentionally manual/safe-only unless a disposable environment is used.
