# Notification Delivery Verification

This focused runbook verifies the current alert-service notification module.
Use it when a change touches notification channels, preferences, provider
adapters, attempt retries, leases, retention, or frontend notification settings.

## Scope

Current implemented providers:

| Channel type | Current behavior |
| --- | --- |
| `WEBHOOK` | Sends canonical alert JSON to `config.url` with an idempotency key. |
| `EMAIL` | Sends canonical alert message through configured mail transport. |
| `TELEGRAM` | Sends canonical alert message through configured Telegram bot API. |

Other channel types are not user-facing frontend options. Backend channel rows
may store forward-compatible string values, but delivery fails until a provider
adapter is registered.

## Setup

1. Start the local stack.
2. Create or reuse a test account and authenticated user.
3. Use a controlled device that can produce an alert through analytics, or use
   an existing alert fixture only for local verification.
4. Configure provider endpoints with test-only credentials:
   - webhook receiver URL for `WEBHOOK`;
   - Spring Mail test transport or sandbox SMTP settings for `EMAIL`;
   - Telegram test bot token and test chat or a configured API-base stub for
     `TELEGRAM`.
5. Keep provider secrets in environment variables or secret storage. Do not put
   secrets in notification channel `config`.

## API Checks

Create one channel per provider through the gateway:

```http
POST /alerts/notification-channels
```

Verify:

- `GET /alerts/notification-channels` returns the created channel for the same
  account only.
- `POST /alerts/notification-channels/{id}/disable` prevents new delivery.
- `POST /alerts/notification-channels/{id}/enable` restores delivery.
- Invalid webhook URLs, invalid email addresses, unsupported Telegram parse
  modes, and unsupported channel types return `400`.
- Secret-like config keys such as `token`, `password`, `apiKey`, `credential`,
  or `webhookSecret` return `400`.

Update preferences:

```http
PUT /alerts/notification-preferences
```

Verify severity filters, channel type filters, device scope, quiet hours,
timezone, and critical quiet-hours bypass.

## Delivery Checks

Trigger or locate an alert that is eligible for notification delivery. Then
query:

```http
GET /alerts/{id}/notification-attempts
```

Verify:

- attempts are created in the same account scope as the alert;
- disabled or preference-suppressed channels do not send provider calls;
- webhook, email, and Telegram attempts use the canonical alert content;
- successful provider outcomes become `SENT`;
- retryable SMTP/network failures and Telegram `429` or `5xx` failures become
  `RETRYING` while attempts remain;
- invalid email config, provider authentication failures, and Telegram
  `400`, `401`, or `403` outcomes become `FAILED`;
- provider response summaries are bounded and do not include secrets.

## Worker And Lease Checks

For backend changes, include focused tests or local evidence that:

- only `PENDING` and due `RETRYING` attempts are claimable;
- an active `PROCESSING` lease prevents another worker from claiming the same
  attempt;
- a stale `PROCESSING` lease can be replayed after lease expiry;
- retry exhaustion ends in `FAILED`;
- notification retention removes only attempts older than the configured TTL.

## Frontend Checks

Open notification settings in the frontend and verify:

- webhook, email, and Telegram forms submit typed payloads through
  `frontend/src/alerts/alertsApi.ts`;
- forms do not expose or submit provider secret fields;
- preference changes persist after reload;
- alert details show notification attempts and provider status/error summaries.

## Evidence

Record:

- user/account id;
- channel ids and channel types;
- preference payload;
- alert id;
- attempt ids, statuses, retry counts, and provider summaries;
- provider stub or sandbox evidence;
- frontend route and screenshot notes when UI is touched;
- relevant backend test command output.
