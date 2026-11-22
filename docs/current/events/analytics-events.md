# Analytics Events

Analytics-service consumes validated telemetry and maintenance policy events,
then publishes alert-driving analytics events through its durable outbox.

## Consumed Topics

| Topic                           | Producer            | Purpose                                                                                    |
| ------------------------------- | ------------------- | ------------------------------------------------------------------------------------------ |
| `telemetry.validated.v1`        | `telemetry-service` | Source for baseline, feature, anomaly, risk, recommendation, and prediction recalculation. |
| `maintenance-window.changed.v1` | `device-service`    | Local maintenance read model for analytics and ML exclusion policy.                        |

Consumers use `processed_events` and service-specific checkpoints for
idempotency and replay safety.

## Produced Topics

| Topic                   | Record                  | Key        | Consumer        |
| ----------------------- | ----------------------- | ---------- | --------------- |
| `anomaly.detected.v1`   | `AnomalyDetectedEvent`  | `deviceId` | `alert-service` |
| `risk-score.changed.v1` | `RiskScoreChangedEvent` | `deviceId` | `alert-service` |

The current record classes are:

- `backend/common/common-events/src/main/java/com/voltcanary/common/events/analytics/AnomalyDetectedEvent.java`
- `backend/common/common-events/src/main/java/com/voltcanary/common/events/analytics/RiskScoreChangedEvent.java`

Both records carry top-level envelope-equivalent fields: `eventId`,
`eventVersion`, `correlationId`, `producer`, `accountId`, and `occurredAt`.

## `anomaly.detected.v1`

Important payload fields:

- `deviceId`
- `anomalyId`
- `ruleId`
- `windowStart`
- `windowEnd`
- `inputHash`
- `type`
- `severity`
- `score`
- `description`
- `detectedAt`

`alert-service` uses `eventId` for idempotency and keeps `anomalyId`, `ruleId`,
calculation window, and `inputHash` on created alerts when present.

## `risk-score.changed.v1`

Important payload fields:

- `deviceId`
- `riskScoreId`
- `windowStart`
- `windowEnd`
- `inputHash`
- `previousScore`
- `currentScore`
- `riskLevel`
- `changedAt`

`alert-service` maps `riskLevel` to alert severity and stores source
traceability fields on the alert and notification payload.

Recommendations and predictions are current API-facing analytics records, while
alert creation is driven by `anomaly.detected.v1` and
`risk-score.changed.v1`.

## `analytics.telemetry.validated.dlq.v1`

Producer: `analytics-service`.

Current consumers: none in this repository.

Key: stable `deadLetterId` in the form `<sourceTopic>:<partition>:<offset>`.

Analytics-service sends failed `telemetry.validated.v1` and
`maintenance-window.changed.v1` records to centralized recovery after the
consumer retry budget (three retries, one second apart). Recovery waits up to
10 seconds for the DLQ send acknowledgement; producer metadata acquisition is
also bounded to 10 seconds. Disabled publication or send failure throws from
recovery, so the original record is not treated as recovered. A retry can create
duplicate DLQ copies with the same key; this is at-least-once recovery.
`reason` contains the exception class rather than an upstream error message.

Payload fields:

- `deadLetterId`
- `sourceTopic`
- `reason`
- `originalPayload`
- `failedAt`
