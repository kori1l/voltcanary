# Alert Service Trust Boundary

Alert service consumes internal analytics events and treats them as trusted only
inside the platform boundary. User-facing calls are still authorized through the
API Gateway user context and device-service ownership checks.

## Trusted Event Inputs

Allowed producer services for alert-generating topics:

| Topic | Allowed producer | Consumer |
| --- | --- | --- |
| `anomaly.detected.v1` | `analytics-service` | `alert-service` |
| `risk-score.changed.v1` | `analytics-service` | `alert-service` |

Current alert lifecycle changes are persisted in `alert_events` and
`notification_attempts`; they are not published to Kafka. Target Kafka
lifecycle topics are documented in
[../../target/alert-lifecycle-events.md](../../target/alert-lifecycle-events.md).

## Enforcement Model

Local development does not configure Kafka ACLs. In non-local environments,
restrict produce permissions so only `analytics-service` can write
`anomaly.detected.v1` and `risk-score.changed.v1`. Restrict consume permissions
so only `alert-service` can consume those topics with the alert consumer group.

Recommended deployment controls:

- separate Kafka principals/service accounts per microservice;
- topic-level produce ACLs for analytics topics;
- topic-level consume ACLs for alert-service consumer groups;
- network policy allowing alert-service to read Kafka and call device-service
  internal endpoints only;
- internal service token rotation through the secret manager, not source code;
- DLQ topics with restricted read access because payloads can contain device
  context.

## User Boundary

API Gateway must pass `X-User-Id`, `X-User-Role`, `X-Account-Id` when
available, and `X-Correlation-Id`. Alert service never trusts client-provided
device ownership directly; regular user reads/actions are checked against
`device-service` internal access endpoints. `ADMIN` and `SUPPORT` can use the
support search workflow across accounts.
