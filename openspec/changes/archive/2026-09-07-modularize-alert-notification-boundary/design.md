# Design: Modularize Alert Notification Boundary

## Current State

`AlertService` and `AlertEscalationService` call `NotificationDeliveryService`
directly. `NotificationDeliveryService` queries notification channels and
creates attempts, while `NotificationDeliveryWorker` reads alert and channel
entities to perform provider delivery.

This is service-local and durable, but it leaves alert lifecycle code coupled to
notification implementation details and makes each new provider adapter more
likely to expand the alert package rather than a dedicated notification module.

## Target Boundary

Keep notification runtime inside `alert-service`, but treat it as an internal
bounded module:

```text
alert lifecycle
  -> AlertNotificationPort
      -> notification application
          -> notification routing / planning / attempts
              -> provider adapters
```

The alert lifecycle side owns decisions about whether an alert exists and which
alert lifecycle event occurred. The notification side owns which channels are
eligible, how attempts are persisted, and how delivery is performed.

## Application Contract

Alert lifecycle code should call an interface similar to:

```java
interface AlertNotificationPort {
    void alertCreated(AlertNotificationRequest request);
    void alertEscalated(AlertNotificationRequest request);
}
```

`AlertNotificationRequest` is a snapshot, not a JPA entity. It should include:

- alert id;
- alert event id or equivalent lifecycle event id;
- account id;
- device id;
- event type;
- alert type;
- severity;
- status;
- title and description;
- analytics source context when available;
- occurred timestamp;
- idempotency key.

The idempotency key should be stable per alert lifecycle event and channel. A
future external service or Kafka publisher can reuse this same request shape.

## Provider Adapter Boundary

Provider adapters should implement a narrow interface similar to:

```java
interface NotificationProviderAdapter {
    NotificationChannelType channelType();
    void validateConfig(NotificationChannelConfig config);
    DeliveryResult deliver(DeliveryCommand command);
}
```

Adapters should only validate provider-specific non-secret config, resolve
provider-specific transport details, perform delivery, and return a normalized
result. They should not decide alert eligibility, quiet hours, retry policy,
attempt leases, idempotency, or persistence state.

## Delivery Flow

1. Alert lifecycle creates or escalates an alert and stores alert audit.
2. It calls `AlertNotificationPort` with an immutable request.
3. Notification orchestration evaluates preferences and enabled channels.
4. Notification planning creates idempotent attempts.
5. After transaction commit, the delivery worker processes due attempts.
6. The worker selects a provider adapter by channel type.
7. The adapter returns sent, retryable failure, permanent failure, or disabled
   outcome.
8. The attempt state is updated with response summary and error code.

## Compatibility

Public routes stay under `/alerts/notification-channels`,
`/alerts/notification-preferences`, and
`/alerts/{id}/notification-attempts` for this change. Documentation should make
clear that these routes are served by the alert-service notification module.

The frontend should keep notification configuration user-facing through the
existing settings and alert investigation surfaces:

- account notification channels remain manageable from the settings UI;
- account notification preferences remain editable from the settings UI;
- alert notification attempts remain visible from alert details;
- API calls remain centralized in `frontend/src/alerts/alertsApi.ts`;
- any touched notification UI should move toward focused notification
  components, hooks, and model helpers rather than adding more state directly to
  large route pages.

This boundary change should not introduce new channel-specific forms, but it
should prepare the frontend structure so provider-specific channel editors can
be added without growing `SettingsPage.tsx` or `AlertsPage.tsx`.

## Future Extraction

The boundary should allow three future implementations without rewriting alert
lifecycle code:

- local module implementation in `alert-service`;
- internal HTTP client to a future `notification-service`;
- outbox or Kafka producer for `notification.requested.v1`.

The current change chooses the local implementation.
