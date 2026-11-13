# Alerts API

The Alert Service stores alert records generated from analytics events and
exposes the user-facing alert workflow.

Base service URL inside Kubernetes:

```text
http://alert-service:8086
```

When accessed through the API Gateway:

```text
http://localhost:8080/alerts
```

## Alert Statuses

| Status | Meaning |
| --- | --- |
| `OPEN` | Alert was created and still needs attention. |
| `ACKNOWLEDGED` | User saw the alert and accepted ownership or investigation. |
| `RESOLVED` | Alert was closed. A resolution reason may be attached. |

## Alert Severities

| Severity | Meaning |
| --- | --- |
| `INFO` | Low-risk signal, usually not user-visible as a high-priority alert. |
| `WARNING` | Medium risk or anomaly. |
| `HIGH` | High-risk anomaly or high-risk score that should be prioritized but is not critical. |
| `CRITICAL` | Critical condition that needs immediate attention. |

Analytics severity mapping:

| Analytics level | Alert severity |
| --- | --- |
| `LOW` | `INFO` |
| `MEDIUM` | `WARNING` |
| `HIGH` | `HIGH` |
| `CRITICAL` | `CRITICAL` |

## Response Model

```json
{
  "id": "0df31e3b-758f-44b5-995a-4a03d9ad76bd",
  "deviceId": "11a45242-7bf4-4f88-a71c-5969299f3bfc",
  "accountId": "2c07eb26-e038-4565-b637-d9787c861477",
  "sourceEventId": "2862dd30-f614-4222-9fa5-41dd21ad87be",
  "anomalyId": "f92a311d-11fb-483f-9119-3410e11c8978",
  "riskScoreId": null,
  "ruleId": "power-spike-v2",
  "calculationWindowStart": "2026-05-14T11:45:00Z",
  "calculationWindowEnd": "2026-05-14T12:00:00Z",
  "calculationInputHash": "e7c1b8d5",
  "alertType": "POWER_SPIKE",
  "title": "Power spike detected",
  "description": "Power moved outside the expected range.",
  "severity": "WARNING",
  "status": "RESOLVED",
  "createdAt": "2026-05-14T12:00:00Z",
  "acknowledgedAt": "2026-05-14T12:10:00Z",
  "resolvedAt": "2026-05-14T12:20:00Z",
  "resolutionReason": "Operator confirmed expected load test."
}
```

## List Alerts

```http
GET /alerts
```

Returns up to the latest 100 alerts ordered by `createdAt` descending.
Supports optional filters: `deviceId`, `status`, `severity`, `alertType`,
`ruleId`, `from`, `to`, and bounded `limit`.

Example:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/alerts
```

## Alert Summary

```http
GET /alerts/summary
```

Returns aggregate counts for the same scoped filter set as `GET /alerts`,
except without `limit`: `deviceId`, `status`, `severity`, `alertType`,
`ruleId`, `from`, and `to`.

Example response:

```json
{
  "total": 7,
  "byStatus": {
    "OPEN": 3,
    "ACKNOWLEDGED": 2,
    "RESOLVED": 2
  },
  "bySeverity": {
    "WARNING": 4,
    "HIGH": 2,
    "CRITICAL": 1
  },
  "byDevice": {
    "11a45242-7bf4-4f88-a71c-5969299f3bfc": 7
  },
  "generatedAt": "2026-05-14T12:00:00Z"
}
```

## Page Alerts

```http
GET /alerts/page?page=0&size=50
```

Returns a page object using the same filters as `GET /alerts`. `page` is
zero-based. `size` defaults to `50` and is capped at `200`.

Example response:

```json
{
  "content": [
    {
      "id": "0df31e3b-758f-44b5-995a-4a03d9ad76bd",
      "deviceId": "11a45242-7bf4-4f88-a71c-5969299f3bfc",
      "alertType": "POWER_SPIKE",
      "title": "Power spike detected",
      "description": "Power moved outside the expected range.",
      "severity": "WARNING",
      "status": "OPEN",
      "createdAt": "2026-05-14T12:00:00Z"
    }
  ],
  "page": 0,
  "size": 50,
  "totalElements": 1,
  "totalPages": 1,
  "hasNext": false
}
```

## Get Alert By ID

```http
GET /alerts/{id}
```

Returns one alert.

Failure cases:

| Status | Reason |
| ---: | --- |
| `404` | Alert was not found. |

## Acknowledge Alert

```http
POST /alerts/{id}/ack
```

Transitions an `OPEN` alert to `ACKNOWLEDGED` and sets `acknowledgedAt`.

The operation is idempotent for non-open alerts. The response contains the
current alert status and ID.

Example response:

```json
{
  "status": "ACKNOWLEDGED",
  "id": "0df31e3b-758f-44b5-995a-4a03d9ad76bd"
}
```

## Resolve Alert

```http
POST /alerts/{id}/resolve
Content-Type: application/json

{
  "reason": "Operator confirmed expected load test."
}
```

Transitions an alert to `RESOLVED`, sets `resolvedAt`, and stores
`resolutionReason`.

If the request body is missing or the reason is blank, the service stores:

```text
Resolved without a reason.
```

The reason is trimmed and limited to 1000 characters.

Example response:

```json
{
  "status": "RESOLVED",
  "id": "0df31e3b-758f-44b5-995a-4a03d9ad76bd"
}
```

## Notification Channels

Notification channel endpoints are scoped by `X-Account-Id`. If that header is
not present, local/dev requests use `X-User-Id` as the account scope.

```http
GET /alerts/notification-channels
POST /alerts/notification-channels
PUT /alerts/notification-channels/{id}
POST /alerts/notification-channels/{id}/enable
POST /alerts/notification-channels/{id}/disable
POST /alerts/notification-channels/{id}/test
GET /alerts/notification-channels/{id}/diagnostics
DELETE /alerts/notification-channels/{id}
```

Create/update request:

```json
{
  "name": "Ops webhook",
  "channelType": "WEBHOOK",
  "config": {
    "url": "https://hooks.example.com/voltcanary/alerts",
    "headers": {
      "X-Team": "ops"
    }
  },
  "credentialsRef": "vault://alerts/webhooks/ops",
  "enabled": true
}
```

Email channel example:

```json
{
  "name": "Ops email",
  "channelType": "EMAIL",
  "config": {
    "recipients": ["ops@example.com"],
    "cc": ["lead@example.com"],
    "fromAlias": "VoltCanary",
    "subjectPrefix": "[VoltCanary]"
  },
  "credentialsRef": "vault://alerts/email/ops",
  "enabled": true
}
```

Telegram channel example:

```json
{
  "name": "Ops Telegram",
  "channelType": "TELEGRAM",
  "config": {
    "chatId": "-1001234567890",
    "parseMode": "MarkdownV2",
    "disableWebPagePreview": true
  },
  "credentialsRef": "vault://alerts/telegram/ops",
  "enabled": true
}
```

Supported channel types are `EMAIL`, `WEBHOOK`, `TELEGRAM`, `SLACK`, `SMS`, and
`PUSH`. Runtime provider adapters currently deliver `WEBHOOK`, `EMAIL`, and
`TELEGRAM`; the remaining channel types are accepted only as
forward-compatible backend configuration and fail delivery until an adapter is
registered. The frontend currently exposes `WEBHOOK`, `EMAIL`, and `TELEGRAM`
channel configuration.

`config` must contain only non-secret settings. Do not send tokens, passwords,
API keys, webhook secrets, or credentials in `config`; store those in a secret
manager and pass only `credentialsRef`. Webhook delivery reads `config.url`.
Email delivery requires `config.recipients` and may include `cc`, `bcc`,
`fromAlias`, and `subjectPrefix`. Telegram delivery requires `config.chatId` and
may include `parseMode` (`Markdown`, `MarkdownV2`, or `HTML`) and
`disableWebPagePreview`.
Keys whose names contain `secret`, `token`, `password`, `credential`,
`api_key`, or `apikey` are rejected even when nested.

Test delivery sends a provider call through one existing channel without
creating an alert or writing alert lifecycle events:

```http
POST /alerts/notification-channels/{id}/test
Content-Type: application/json

{
  "sample": {
    "title": "Ops delivery check",
    "description": "Operator-triggered notification delivery test.",
    "alertType": "NOTIFICATION_TEST",
    "severity": "INFO"
  }
}
```

The response is a notification attempt with `deliveryEventType:
"NOTIFICATION_TEST"` and `alertId: null`. The same recursive secret-key policy
applies to `sample`; secret-like keys are rejected before an attempt is
persisted.

Channel diagnostics are read-only and do not call providers:

```http
GET /alerts/notification-channels/{id}/diagnostics
```

Example response:

```json
{
  "channelId": "d0eb3f57-6cb1-445a-94e1-677442126fd3",
  "accountId": "2c07eb26-e038-4565-b637-d9787c861477",
  "channelType": "WEBHOOK",
  "enabled": true,
  "adapterRegistered": true,
  "lastSuccessfulDeliveryAt": "2026-09-08T10:15:30Z",
  "lastFailureAt": null,
  "lastFailureErrorCode": null,
  "lastFailureSummary": null,
  "retryingCount": 0,
  "failedCount": 0,
  "pendingCount": 0,
  "oldestPendingOrRetryingAgeSeconds": null,
  "lastTestAttemptId": "68fdfd2a-5966-4417-a32a-7d088a869cc7",
  "lastTestAttemptStatus": "SENT",
  "lastTestRetryCount": 0,
  "lastTestMaxAttempts": 3,
  "lastTestNextAttemptAt": "2026-09-08T10:15:29Z",
  "lastTestRetryExhausted": false
}
```

Notification delivery is implemented by the alert-service notification module,
not by alert lifecycle services. Alert creation and escalation pass immutable
notification request snapshots across `AlertNotificationPort`; the notification
module evaluates preferences and enabled channels, then creates durable
`notification_attempts` rows in the same transaction as the lifecycle change.
Background workers claim due attempts with row leases and select provider
adapters from a registry keyed by `channelType`.

The webhook adapter requires `config.url`, sends an `ALERT_CREATED` or
`ALERT_ESCALATED` JSON payload to that URL, and returns a normalized delivery
result. Email and Telegram adapters use the same canonical alert message, then
apply provider-specific formatting and routing. Email accepted/sent responses
map to `SENT`; SMTP/network send failures are retryable, while invalid config
or authentication failures are permanent. Telegram success maps to `SENT` and
stores the Telegram `message_id` when present; HTTP `429` and `5xx` responses
are retryable, while `400`, `401`, `403`, and other invalid chat/config
responses are permanent failures. The notification application layer owns retry
decisions and attempt state transitions. HTTP deliveries include an
`Idempotency-Key` header. Secrets such as webhook signing keys, bot tokens,
SMTP passwords, and provider API keys must be resolved from `credentialsRef` or
service-level configuration, not stored in channel config.

Retryable failures use bounded application-layer backoff. Existing deployments
keep the previous one-second fixed retry behavior unless they configure a
larger multiplier, max delay, or jitter. Attempts store their max-attempt
context and expose `nextAttemptAt` and `retryExhausted` so operators can
understand pending retries and exhaustion without reading database rows.

Operational provider configuration:

| Environment variable | Purpose |
| --- | --- |
| `ALERT_NOTIFICATIONS_MAX_ATTEMPTS` | Maximum provider call attempts, including the initial call. Defaults to `3`. |
| `ALERT_NOTIFICATIONS_RETRY_INTERVAL` | Initial retry delay and compatibility setting for the former fixed retry delay. Defaults to `1s`. |
| `ALERT_NOTIFICATIONS_RETRY_MULTIPLIER` | Exponential backoff multiplier. Defaults to `1.0` for fixed delay compatibility. |
| `ALERT_NOTIFICATIONS_MAX_RETRY_DELAY` | Upper bound for computed retry delay. Defaults to the retry interval. |
| `ALERT_NOTIFICATIONS_RETRY_JITTER_PERCENT` | Optional jitter percentage applied inside the max delay cap. Defaults to `0`. |
| `ALERT_NOTIFICATIONS_EMAIL_FROM_ADDRESS` | Optional default sender address for email notifications. SMTP host, username, and password are supplied through Spring Mail configuration such as `SPRING_MAIL_HOST`, `SPRING_MAIL_USERNAME`, and `SPRING_MAIL_PASSWORD`. |
| `ALERT_NOTIFICATIONS_TELEGRAM_API_BASE_URL` | Optional Telegram API base URL override for tests or private gateways. Defaults to `https://api.telegram.org`. |
| `ALERT_NOTIFICATIONS_TELEGRAM_BOT_TOKEN` | Service-level Telegram bot token. Keep it out of channel config JSON. |

## Notification Preferences

```http
GET /alerts/notification-preferences
PUT /alerts/notification-preferences
```

Preferences are scoped by `X-Account-Id`. If the header is missing in local/dev
flows, `X-User-Id` is used as the account scope.

The frontend exposes webhook, email, Telegram channel, and preference
configuration from focused notification settings components and keeps requests
centralized in `frontend/src/alerts/alertsApi.ts`. Provider-specific forms must
submit only non-secret channel config and use `credentialsRef` for secret
manager references.

Update request:

```json
{
  "enabled": true,
  "severities": ["WARNING", "HIGH", "CRITICAL"],
  "channelTypes": ["WEBHOOK", "SLACK"],
  "deviceIds": ["11a45242-7bf4-4f88-a71c-5969299f3bfc"],
  "quietHoursStart": "22:00:00",
  "quietHoursEnd": "07:00:00",
  "timezone": "Europe/Belgrade",
  "criticalBypassQuietHours": true
}
```

Empty `severities`, `channelTypes`, or `deviceIds` means all values are allowed.
Quiet hours can cross midnight. `CRITICAL` alerts bypass quiet hours when
`criticalBypassQuietHours` is `true`.

`configurableRuleAlertsEnabled` controls notifications for newly created
configurable-rule alerts. It defaults to `false`. A PUT that omits the field
preserves the current setting. Delivery requires the account preference and
the configured channel to be enabled and still applies severity, device and
quiet-hours filters.
Retracting an alert disables its queued creation attempts. The worker
also skips resolved configurable-rule alerts when preparing a delivery.
Delivery already in progress can finish.

## Notification Attempts

```http
GET /alerts/{id}/notification-attempts
```

Returns notification delivery attempts for an alert ordered by newest first.
Access is checked through the same alert ownership rules as `GET /alerts/{id}`.

Attempt statuses:

| Status | Meaning |
| --- | --- |
| `PENDING` | Attempt was created and is waiting for delivery. |
| `PROCESSING` | A worker claimed the attempt with a lease and is delivering it. |
| `SENT` | Provider accepted or completed delivery. |
| `FAILED` | Delivery failed and is not currently retrying. |
| `RETRYING` | Delivery failed and is scheduled for another attempt. |
| `DISABLED` | Channel was disabled, so delivery was skipped. |

Example response:

```json
[
  {
    "id": "68fdfd2a-5966-4417-a32a-7d088a869cc7",
    "alertId": "0df31e3b-758f-44b5-995a-4a03d9ad76bd",
    "channelId": "d0eb3f57-6cb1-445a-94e1-677442126fd3",
    "accountId": "account-123",
    "channelType": "WEBHOOK",
    "deliveryEventType": "ALERT_CREATED",
    "status": "SENT",
    "retryCount": 1,
    "maxAttempts": 3,
    "nextAttemptAt": "2026-05-14T12:00:00Z",
    "retryExhausted": false,
    "lastErrorCode": null,
    "providerResponseSummary": "HTTP 202",
    "providerMessageId": null,
    "createdAt": "2026-05-14T12:00:00Z",
    "updatedAt": "2026-05-14T12:01:00Z",
    "sentAt": "2026-05-14T12:01:00Z"
  }
]
```

## Alert Creation

Alerts are created from internal analytics events:

- `AnomalyDetectedEvent`
- `RiskScoreChangedEvent`

When available, alert rows and API responses keep analytics correlation fields:
`sourceEventId`, `anomalyId`, `riskScoreId`, `ruleId`,
`calculationWindowStart`, `calculationWindowEnd`, and
`calculationInputHash`. Webhook payloads and alert lifecycle event payloads
include the same source context.

The service deduplicates alerts by:

```text
deviceId:alertType:severity:yyyyMMddHH
```

This prevents repeated events for the same device, alert type, severity, and
hour from creating duplicate alert rows while the matching alert is still
`OPEN` or `ACKNOWLEDGED`.

If the matching alert was already `RESOLVED`, a new source event creates a new
`OPEN` alert. Repeated events in the same hour then deduplicate into that new
unresolved alert.

Maintenance windows are applied before new alert creation. Alert-service
consumes `maintenance-window.changed.v1` into a local read model and suppresses
incoming `AnomalyDetectedEvent` or `RiskScoreChangedEvent` records when the
event calculation window overlaps a non-cancelled maintenance window with
`suppressAlerts=true`. Suppressed events are stored in `alert_suppressions` for
audit and the analytics inbox entry is marked as `NO_ALERT`.

When a new or expanded maintenance window overlaps existing unresolved alerts,
alert-service auto-resolves those suppressible alerts with resolution reason:

```text
Auto-resolved because the device is in a maintenance window.
```

The auto-resolution is recorded in `alert_events` with event type
`AUTO_RESOLVED_BY_MAINTENANCE` and in `alert_suppressions`.

When device authorization is enabled, alert creation resolves `accountId` from
the trusted device-service internal owner lookup:

```http
GET /internal/devices/{deviceId}/owner
```

The deduplication policy intentionally uses source event id first, then an
active condition window by `deviceId + alertType + severity + hour`. Source ids
provide idempotency for exact replayed analytics events. The condition window
groups repeated measurements for the same active issue without requiring every
analytics rule to emit a stable anomaly id.

## Support Search

```http
GET /alerts/support/search
```

Searches alerts across accounts for `ADMIN` and `SUPPORT` users. Supports
`accountId`, `deviceId`, `status`, `severity`, `alertType`, `ruleId`, `from`, `to`,
`page`, and `size`. Regular users receive `403`.

## Retention

Retention cleanup is scheduled by alert-service:

| Record type | Default TTL |
| --- | ---: |
| Resolved alerts | 180 days |
| Alert events | 365 days |
| Notification attempts | 90 days |

Configure with `ALERT_RETENTION_RESOLVED_ALERT_TTL`,
`ALERT_RETENTION_EVENT_TTL`, and `ALERT_RETENTION_NOTIFICATION_ATTEMPT_TTL`.

## Error Responses

The OpenAPI metadata documents common error responses:

| Status | Reason |
| ---: | --- |
| `400` | Invalid UUID, enum, pagination, timezone, notification config, or request body. |
| `401` | Missing authenticated user context. |
| `403` | User lacks the required admin/support role. |
| `404` | Alert or notification channel was not found, or it is outside caller scope. |
| `503` | Device authorization service is unavailable. |

## Escalation

The service scans unacknowledged `OPEN` alerts and records one `ESCALATED`
event when the configured severity threshold is exceeded:

| Severity | Default threshold |
| --- | ---: |
| `WARNING` | 30 minutes |
| `HIGH` | 15 minutes |
| `CRITICAL` | 5 minutes |

Escalated alerts remain `OPEN`; the escalation is stored in `alert_events` for
audit/history and triggers another notification delivery pass for eligible
channels. Webhook payloads use `eventType: ALERT_ESCALATED`; email and Telegram
deliveries use the same canonical notification message with provider-specific
formatting.

## Event Audit

The service stores internal alert event history for:

- alert creation
- acknowledgement
- resolution
- escalation

Resolution audit payload includes the reason:

```json
{
  "alertId": "0df31e3b-758f-44b5-995a-4a03d9ad76bd",
  "reason": "Operator confirmed expected load test."
}
```

## Console page continuation (2026-10-08)

The inbox uses the existing `GET /alerts/page` with `page` and `size=100` plus
the existing authorized filters. Its response is `{content, page, size,
totalElements, totalPages, hasNext}`. Ordering is `createdAt DESC, id DESC`.
The classic `GET /alerts` retains its bounded array payload for device/dashboard
consumers. No cursor endpoint or response migration was introduced.

Page totals come from the server. The console's combined Active (OPEN +
ACKNOWLEDGED) view filters the returned page locally and labels its total unknown.
It still follows server hasNext even if the current page has no active records.
Severity sorting applies within the loaded page. A separate summary read is
scoped to filters and does not rerun merely when moving to another page.

## Dashboard incident owner read

Internal POST `/internal/dashboard/incidents` requires an internal token and
signed viewer bearer and renews the active owner/device scope, even with legacy
authorization disabled. Full-scope counts distinguish active OPEN/ACKNOWLEDGED,
critical active and alerts created in the half-open interval. Stable pages≤50
have separate total/hasNext; notification attempts are not counted. The result
contains no incident write actions and reads do not acknowledge, resolve or send
notifications. Its typed wire contract and snapshot consistency limits are in
[dashboard-api.md](dashboard-api.md). Existing alert routes remain unchanged.

## Configurable rule identity reservations

`POST /alert-rules/configurable/review-reservations` accepts exactly
`{kind:"RULE",outputKeys:string[0..64],idempotencyKey:string(1..160)}`. Technical
output keys are distinct ASCII tokens matching `[A-Za-z][A-Za-z0-9_]{0,63}`.
The idempotency key preserves Unicode, cannot be blank or contain NUL and is
bounded by Unicode code points. Unknown/duplicate fields, unsafe numbers, malformed
UTF-8 and invalid keys fail before owner I/O or reservation effects.

The signed actor's current ACTIVE personal account is resolved through the device
owner with the internal service token, fixed route, bounded read transport and a
closed identity response. Caller account/role headers cannot replace signed
identity. The existing legacy authorization toggle does not disable this check.
Owner failure or malformed identity produces503; missing/revoked authority403.

The response is `{reviewId,ruleId,receiptId,outputFieldIdsByKey,expiresAt,status}`.
`GET /alert-rules/configurable/reviews/{reviewId}` inspects the same owned
reservation and renews authority checks. Foreign/missing reviews return404.
Same account/key and the same set of output keys return the original identifiers
and absolute expiry; different payloads return409. Expired replay returns the same
IDs with status EXPIRED, without renewing the lease. Output order is immaterial.

V020 stores `cfg_rule_owner_fence` and `cfg_rule_review_reservation`. A local
account row serializes key decisions, expiry cleanup and the quota of100 outstanding
reservations. It does not supply account authority. First-use insertion races and
concurrent identical/conflicting requests are serialized. The owner transaction
uses the database clock for a15-minute expiry. Reads do not create local fences;
the reservation stores a key hash plus original text so Unicode keys retain exact
identity. Changed payloads and failed authorizations never mint replacement IDs.

This endpoint reserves identities only. It creates no alert-rule definition,
automation grant, alert, delivery intent or dispatch receipt outcome. Grant and
evidence-policy owners, source readiness, RULE preparation/confirmation, activation,
effect authorization and temporal runtime retain separate unfinished gates.

## Reviewed automation grants

The implemented `source-native-fields-grant-1` profile permits owner-local ALERT
only, with no delivery channels. It creates permission metadata for one reserved
RULE; it does not activate a rule or prove normalized capture/evaluator readiness.
All create/preview/inspect operations require the signed actor's fresh ACTIVE
account. Source/native descriptions are fetched over fixed authenticated owner
routes, strictly decoded and checked against that actor/account and exact fields.

| Operation | Route and exact body |
| --- | --- |
| Reserve | `POST /alert-rules/configurable/grant-review-reservations`: `{kind:"AUTOMATION_GRANT",ruleReviewId,idempotencyKey}` |
| Prepare | `POST /alert-rules/configurable/grant-reviews`: `{reservationId,definition:{sourceBindings:[{id,schemaRevision,fieldIds}],datasets:[{id,schemaRevision,fieldIds}],expiresAt}}` |
| Inspect review | `GET /alert-rules/configurable/grant-reviews/{id}` |
| Apply | `POST /alert-rules/configurable/grant-reviews/{id}/apply`: `{reviewRevision,reviewDigest,idempotencyKey}` |
| Reconcile | `POST /alert-rules/configurable/grant-reviews/{id}/reconcile`: `{}` |
| Inspect permission | `GET /alert-rules/configurable/grants/{id}` |
| Revoke | `POST /alert-rules/configurable/grants/{id}/revoke`: `{expectedEpoch:string,idempotencyKey}` |
| Inspect revocation receipt | `GET /alert-rules/configurable/grant-revocations/{id}` |

At least one source binding is required; each source/dataset list is bounded by16
and each distinct field set by64. Source-only permission is allowed. Expiry is an
explicit UTC instant, microsecond precision, at most30 days from database time.
The original RULE reservation fixes the review lease; replay cannot renew it.
Reordered sets normalize identically. Changed preview increments revision and
changes its digest, invalidating earlier consent. Unsupported legacy numeric
fields cannot be selected. Native permissions pin schema/generation, not row
dataVersion; this profile does not claim an input data snapshot.

V021 persists reviews, permissions and revocation receipts. Prepare binds scope,
owner read pins, IDs, actor/account, revision and lease in the review digest.
Apply accepts only the stored scope and original key, refreshes owner checks and
commits APPLYING before the effect transaction. Grant creation and SUCCEEDED
receipt commit atomically. Lost acknowledgement returns the known receipt with
UNKNOWN/202; exact retry can recover that intent. Reconcile only reads state and
never dispatches. A grant absent after its permission lifetime or review lease
has expired is a proven no-effect failure under the same owner fence.

Epochs are positive decimal strings. Revoke reserves its receipt before the atomic
REVOKED/epoch increment and result commit. Conflicting concurrent epochs fail
without another increment. Revocation and its minimal receipt remain accessible
to the signed original owner after account/source access is revoked, so permission
can still be removed. They disclose no new source values. Completed creation
receipts retain `createdEpoch:"1"` even after revocation; GET grant provides the
current state. Active grant inspection refreshes source/native pins.

Database expiry checks use a fresh clock after lock acquisition:
PostgreSQL `clock_timestamp()` and H2 statement-start time. Transaction-start
timestamps cannot extend authority during a lock wait. The local epoch owner is
implemented; atomic alert/effect fencing against that epoch remains unfinished.

## Reviewed evidence policies

The implemented `private-reference-evidence-1` profile owns immutable policy
metadata for one existing grant/rule and exact grant epoch. It permits only
`PRIVATE_REFERENCES`, with explicit `retentionDays`1..30 and
`maxObservationReferences`1..100. Policy creation does not write evidence, run
retention cleanup, activate a rule, disclose input values or authorize delivery.

| Operation | Route and exact body |
| --- | --- |
| Reserve | `POST /alert-rules/configurable/evidence-policy-reservations`: `{kind:"EVIDENCE_POLICY",grantId,expectedGrantEpoch:string,idempotencyKey}` |
| Prepare | `POST /alert-rules/configurable/evidence-policy-reviews`: `{reservationId,definition:{mode:"PRIVATE_REFERENCES",retentionDays,maxObservationReferences}}` |
| Inspect review | `GET /alert-rules/configurable/evidence-policy-reviews/{id}` |
| Apply | `POST /alert-rules/configurable/evidence-policy-reviews/{id}/apply`: `{reviewRevision,reviewDigest,idempotencyKey}` |
| Reconcile | `POST /alert-rules/configurable/evidence-policy-reviews/{id}/reconcile`: `{}` |
| Inspect policy | `GET /alert-rules/configurable/evidence-policies/{id}` |

Signed identity and fresh ACTIVE account authority remain mandatory, including
with legacy authorization disabled. First reservation, prepare and uncompleted
apply refresh the actual grant's source/native owner checks. IDs, rule and scope
are derived from the stored grant; request JSON cannot supply another actor,
account, rule, field list, recipient or output channel. Epochs use exact positive
decimal strings. The15-minute database-clock review lease is capped by grant expiry.

V022 stores reviews and policies. Review responses expose reserved policy/review/
receipt IDs, exact grant epoch/definition digest, revision, lease, normalized
definition and consent digest. Changed limits increment the review revision and
invalidate earlier consent; replay never renews the lease. Same-key reservation
replay can return its original owned receipt after grant revoke/expiry, without
minting a resource or restoring usability. Missing/foreign resources return404.
Outstanding reviews are bounded by100; retained policies by1000 per account.

Durable APPLYING precedes policy creation. The existing account/grant SQL fence
serializes creation against revocation; fresh database time and the exact grant
epoch/scope are checked before policy and SUCCEEDED receipt commit atomically.
Lost acknowledgement preserves the known receipt as UNKNOWN/202; retry uses the
same reviewed intent. Reconcile never creates a policy: absence after lease
expiry or irreversible grant change proves FAILED with a no-effect outcome.
Completed create receipts remain immutable after grant revocation.

Policy inspection returns immutable revision1 and effective `{status,usable}`.
Source/native authority is refreshed through the real grant owner, then its local
epoch is read again under the grant fence, so a revoke during remote checks cannot
be returned as usable. An expired/revoked/different grant epoch makes the policy
INACTIVE; it does not silently rebind to a new epoch. Inactive policies remain
private owner metadata. Owner transport failure/corrupt persisted state returns503.

`ConfigurableOwnerReads.evidencePolicy` and the strict common-vcl decoder carry
the same ordinary signed GET contract to analytics. `EvidencePolicySnapshots`
requires a separately authenticated grant snapshot, checks the exact scope digest,
epoch and expiry bound and refreshes both owners before each pin use. The pin
binds policy/grant/rule/actor/account identities, epoch, limits and expiry; it
does not qualify capture, evidence retention execution or temporal runtime.
