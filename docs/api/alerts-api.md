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
`from`, `to`, and bounded `limit`.

Example:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/alerts
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
DELETE /alerts/notification-channels/{id}
```

Create/update request:

```json
{
  "name": "Ops webhook",
  "channelType": "WEBHOOK",
  "config": {
    "urlRef": "ops-primary-webhook",
    "headers": {
      "X-Team": "ops"
    }
  },
  "credentialsRef": "vault://alerts/webhooks/ops",
  "enabled": true
}
```

Supported channel types are `EMAIL`, `WEBHOOK`, `TELEGRAM`, `SLACK`, `SMS`, and
`PUSH`.

`config` must contain only non-secret settings. Do not send tokens, passwords,
API keys, webhook secrets, or credentials in `config`; store those in a secret
manager and pass only `credentialsRef`.

All supported channel types are delivered asynchronously after alert creation
and escalation. For the current provider-neutral implementation, each channel
requires `config.url`; the service sends an `ALERT_CREATED` or
`ALERT_ESCALATED` JSON payload to that URL and records each delivery as a
notification attempt. Secrets such as webhook signing keys, bot tokens, SMTP
passwords, and provider API keys must be resolved from `credentialsRef` by the
deployment/provider adapter, not stored in channel config.

## Notification Preferences

```http
GET /alerts/notification-preferences
PUT /alerts/notification-preferences
```

Preferences are scoped by `X-Account-Id`. If the header is missing in local/dev
flows, `X-User-Id` is used as the account scope.

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
    "status": "SENT",
    "retryCount": 1,
    "lastErrorCode": null,
    "providerResponseSummary": "HTTP 202",
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
`accountId`, `deviceId`, `status`, `severity`, `alertType`, `from`, `to`,
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
audit/history and triggers another notification delivery pass with webhook
payload `eventType: ALERT_ESCALATED`.

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
