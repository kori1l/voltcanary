# Design: Add Email And Telegram Notification Adapters

## Channel Types

The existing supported channel type list includes `EMAIL` and `TELEGRAM`. This
change turns those types into real provider adapters.

`EMAIL` and `TELEGRAM` remain provider-specific types. The backend should not
introduce a generic `MESSENGER` channel because each messenger has different
configuration, credential, payload, and retry semantics.

## Email Adapter

Email delivery should use a dedicated adapter selected for `EMAIL` channels.
The adapter should support service-level SMTP configuration or another
deployment-selected mail sender abstraction. Channel config contains routing
metadata only.

Example non-secret channel config:

```json
{
  "recipients": ["ops@example.com"],
  "cc": ["lead@example.com"],
  "fromAlias": "VoltCanary",
  "subjectPrefix": "[VoltCanary]"
}
```

Validation rules:

- `recipients` is required and must contain at least one valid email address.
- `cc` and `bcc`, if present, must contain valid email addresses.
- `subjectPrefix` and `fromAlias` are bounded strings.
- Channel config must not contain passwords, tokens, API keys, SMTP secrets, or
  provider credentials.
- `credentialsRef` or service-level mail configuration supplies credentials.

Delivery result mapping:

- Accepted or sent mail maps to `SENT`.
- Transient SMTP/network errors map to retryable failure while attempts remain.
- Invalid recipient/config/provider permanent errors map to `FAILED`.

## Telegram Adapter

Telegram delivery should use a dedicated adapter selected for `TELEGRAM`
channels. Channel config contains chat routing metadata only. The bot token must
come from `credentialsRef` or deployment-level configuration.

Example non-secret channel config:

```json
{
  "chatId": "-1001234567890",
  "parseMode": "MarkdownV2",
  "disableWebPagePreview": true
}
```

Validation rules:

- `chatId` is required and must be a non-blank bounded string.
- `parseMode`, if present, must be one of the supported Telegram parse modes.
- `disableWebPagePreview`, if present, must be boolean.
- Channel config must not contain bot tokens or webhook secrets.

Delivery result mapping:

- Telegram API success maps to `SENT` and stores the provider message id when
  available.
- HTTP `429` and `5xx` responses map to retryable failure while attempts remain.
- HTTP `400`, `401`, `403`, and invalid chat/config failures map to `FAILED`.

## Message Formatting

Both adapters should use the notification module's canonical alert notification
message. Provider adapters may apply provider-specific escaping and formatting,
but they should not create independent business wording for severity, alert
type, source context, or alert links.

## Frontend Configuration

Users should be able to configure notification delivery from the frontend rather
than editing JSON manually. The settings surface should support:

- a channel type selector for `WEBHOOK`, `EMAIL`, and `TELEGRAM`;
- shared channel fields: name, enabled state, credentials reference when needed,
  and non-secret provider config;
- email fields: recipients, optional cc/bcc, from alias, and subject prefix;
- Telegram fields: chat id, optional parse mode, and web preview toggle;
- account preferences: enabled flag, severities, channel types, device scope,
  quiet hours, timezone, and critical quiet-hours bypass;
- inline validation that mirrors backend validation for required fields,
  supported enum values, bounded string lengths, and secret-like config keys.

Route pages should not own the provider-specific form logic directly. New UI
behavior should live in focused notification settings components, hooks, and
model helpers, with `frontend/src/alerts/alertsApi.ts` remaining the typed API
boundary.

The frontend may expose `credentialsRef` as an advanced non-secret reference or
use deployment defaults when configured. It must not collect SMTP passwords,
Telegram bot tokens, API keys, webhook secrets, or other provider secrets in
channel config forms.

## Observability

Metrics should retain the existing notification delivery dimensions and include
`channelType=EMAIL` or `channelType=TELEGRAM`. Logs should include attempt id,
alert id, channel id, provider outcome, and correlation/idempotency key when
available, without logging credentials or full provider secrets.

## Testing

Use focused tests with mocked or local HTTP/provider boundaries:

- email config validation and secret rejection;
- Telegram config validation and secret rejection;
- frontend email and Telegram channel form validation;
- frontend notification preference editing for the channel type filters and
  quiet-hours controls;
- successful delivery result mapping;
- retryable provider failure mapping;
- permanent provider failure mapping;
- attempt idempotency is unchanged for repeated alert lifecycle events.
