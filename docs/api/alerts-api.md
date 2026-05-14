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
| `CRITICAL` | High-risk anomaly or high-risk score. |

## Response Model

```json
{
  "id": "0df31e3b-758f-44b5-995a-4a03d9ad76bd",
  "deviceId": "11a45242-7bf4-4f88-a71c-5969299f3bfc",
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

Example:

```powershell
Invoke-WebRequest -UseBasicParsing http://localhost:8080/alerts
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

## Alert Creation

Alerts are created from internal analytics events:

- `AnomalyDetectedEvent`
- `RiskScoreChangedEvent`

The service deduplicates alerts by:

```text
deviceId:alertType:severity:yyyyMMddHH
```

This prevents repeated events for the same device, alert type, severity, and
hour from creating duplicate alert rows.

## Event Audit

The service stores internal alert event history for:

- alert creation
- acknowledgement
- resolution

Resolution audit payload includes the reason:

```json
{
  "alertId": "0df31e3b-758f-44b5-995a-4a03d9ad76bd",
  "reason": "Operator confirmed expected load test."
}
```
