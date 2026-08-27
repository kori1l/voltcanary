# Identity Events

Identity-service publishes account lifecycle events through the identity
outbox. Device-service consumes them to keep its local account lifecycle state
aligned with user status changes.

## `identity.account-lifecycle.v1`

Producer: `identity-service`.

Consumer: `device-service`.

Kafka key:

```text
userId
```

Record:

```text
com.voltcanary.common.events.identity.IdentityAccountLifecycleEvent
```

JSON shape:

```json
{
  "eventId": "c995feec-799d-47d9-a55c-2ac8c00c2451",
  "eventVersion": "v1",
  "userId": "8e13f0b1-8d43-4978-a131-3b65f3e46c1c",
  "email": "operator@example.com",
  "status": "DISABLED",
  "occurredAt": "2026-08-27T10:00:00Z",
  "correlationId": null,
  "producer": "identity-service",
  "accountId": "8e13f0b1-8d43-4978-a131-3b65f3e46c1c"
}
```

Status values emitted by identity-service:

| Identity user status | Event status |
| --- | --- |
| `ACTIVE` | `ACTIVE` |
| `DELETED` | `DELETED` |
| any other status | `DISABLED` |

Device-service handles the event idempotently through its `processed_events`
table and calls the same account synchronization path exposed by the internal
HTTP endpoint:

```http
POST /internal/accounts/users/{userId}/lifecycle
```

When the status is `DELETED`, device-service applies account cleanup policy:
owned devices are decommissioned, owned data sources are disabled/revoked, and
historical registry records remain for audit and telemetry traceability.
