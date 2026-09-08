# Design: Harden Notification Delivery Operations

## Current State

The notification module is local to `alert-service` and owns channel
configuration, preferences, durable attempts, worker leases, retries, and
provider adapter dispatch. Operators can manage webhook, email, and Telegram
channels and can inspect attempts attached to a real alert.

The main operating gaps are:

- a channel cannot be tested safely without generating an alert;
- retry timing is fixed and does not clearly expose exhaustion or next retry
  state to operators;
- per-channel health must be inferred from attempt rows rather than a scoped
  API response;
- the frontend can configure channels but does not yet provide delivery
  diagnostics or a test-send workflow.

## Test Delivery

Add a public alert-service route under the existing notification channel
surface:

```http
POST /alerts/notification-channels/{id}/test
```

The route should be scoped exactly like channel update/delete operations:
`X-Account-Id` when present, otherwise the local/dev `X-User-Id` fallback.

The request body may include optional non-secret sample fields such as device
label, severity, alert type, title, and description. Missing fields should use
bounded deterministic defaults. The backend must reject secret-like keys using
the same recursive config-key policy used for channel config.

Test delivery should create an auditable notification attempt with a dedicated
delivery event type such as `NOTIFICATION_TEST`. It must not create an alert,
change alert status, write alert lifecycle events, or bypass provider adapter
validation. The attempt should still use the provider registry, canonical
message factory, credentials resolution behavior, leases, metrics, and
normalized delivery result mapping.

## Retry Policy

Keep retry ownership in the notification application layer. Replace fixed retry
delay behavior with configurable bounded backoff:

- initial retry delay;
- exponential multiplier;
- maximum retry delay;
- optional jitter percentage;
- maximum attempts.

The implementation should persist enough attempt state to explain:

- current status;
- retry count and maximum attempts;
- next attempt timestamp when retrying;
- last error code;
- bounded provider response summary;
- provider message id when available;
- whether the attempt is exhausted.

Existing attempts and existing environment values must remain compatible. If a
new migration is needed, defaults should preserve current behavior unless the
configuration opts into wider backoff.

## Channel Diagnostics

Add a read-only channel diagnostics route:

```http
GET /alerts/notification-channels/{id}/diagnostics
```

The response should summarize recent attempt outcomes for that channel without
returning secrets or full provider payloads. Include:

- channel id, type, enabled state, and account scope;
- whether a provider adapter is registered;
- last successful delivery timestamp;
- last failure timestamp, error code, and bounded redacted summary;
- current retrying/failed/pending counts;
- oldest retrying or pending attempt age;
- last test delivery attempt id and status when present.

Diagnostics are advisory. They must not perform provider calls, rotate secrets,
or mutate attempt state.

## Frontend UX

Keep `SettingsPage.tsx` as composition only. Add diagnostics and test-send
behavior under focused notification feature modules.

The settings surface should let operators:

- see channel health/status from the diagnostics route;
- trigger a test send from an existing channel;
- see the resulting test attempt status, next retry, or failure summary;
- edit retry-related preferences/configuration only if backend exposes writable
  controls in this change;
- avoid entering SMTP passwords, Telegram bot tokens, webhook secrets, or other
  provider credentials in visible forms.

Typed request/response models and API helpers belong in
`frontend/src/alerts/alertsApi.ts`.

## Observability And Security

Metrics should keep dimensions bounded: channel type, status, and outcome class.
Logs may include attempt id, channel id, alert id for real deliveries,
correlation id, and normalized error code. Logs must not include credentials,
full provider payloads, mail recipient bodies beyond bounded routing summaries,
or Telegram bot tokens.

Test deliveries should be clearly distinguishable from real alert deliveries in
attempt rows, metrics, frontend copy, and verification evidence.

## Testing

Backend tests should cover:

- test delivery authorization and account scoping;
- test delivery uses the provider registry and records an attempt;
- test delivery does not create or mutate alert lifecycle rows;
- retry backoff calculation, caps, jitter bounds, and exhaustion;
- diagnostics response for success, retrying, failed, disabled, and unsupported
  adapter states;
- secret-like field rejection in test-delivery request bodies.

Frontend tests should cover:

- diagnostics loading and empty/error states;
- triggering test delivery from channel settings;
- rendering sent, retrying, failed, disabled, and unsupported states;
- preserving provider-specific channel forms and secret rejection behavior.
