# Change Proposal: Harden Notification Delivery Operations

## Why

Alert notifications now have a modular delivery boundary and concrete webhook,
email, and Telegram provider adapters. Operators can configure channels and
inspect attempts, but production-like operation still lacks safe test delivery,
clear provider diagnostics, bounded retry/backoff policy, and UI/API support for
triaging delivery failures without reading database rows or provider logs.

This change improves the notification module as an operable alert delivery
surface while keeping it inside `alert-service`.

## What Changes

- Add an authenticated notification channel test-delivery workflow.
- Record test deliveries as auditable notification attempts that do not create
  or mutate alert lifecycle records.
- Add bounded retry policy controls for exponential backoff, jitter, maximum
  delay, and retry exhaustion visibility.
- Expose per-channel delivery diagnostics based on recent attempts, redacted
  provider summaries, retry state, and last successful delivery.
- Keep diagnostics and test delivery scoped by account ownership and existing
  alert-service user context.
- Add focused notification settings UI for test delivery and channel health
  without putting provider secrets in forms or logs.
- Document verification steps for provider success, retryable failure,
  permanent failure, disabled/suppressed delivery, and secret redaction.

## Non-Goals

- Do not extract a separate `notification-service`.
- Do not introduce Kafka notification request topics.
- Do not add new provider types such as Slack, SMS, or push notifications.
- Do not expose SMTP passwords, Telegram bot tokens, webhook secrets, or API
  keys in API responses, logs, frontend state, or docs examples.
- Do not make test delivery bypass account authorization or provider adapter
  validation.
- Do not require a real alert row for test delivery.

## Impacted Areas

- `backend/services/alert-service/src/main/java/com/voltcanary/alert/notification/**`
- `backend/services/alert-service/src/main/resources/db/migration/**`
- `backend/services/alert-service/src/test/java/com/voltcanary/alert/**`
- `frontend/src/alerts/alertsApi.ts`
- `frontend/src/features/notifications/**`
- `frontend/src/pages/SettingsPage.tsx`
- `frontend/src/**/*.test.tsx`
- `docs/current/api/alerts-api.md`
- `docs/current/architecture/10-consistency-contracts.md`
- `docs/current/flows/notification-delivery-verification.md`
- `docs/target/notification-delivery-operations.md`
- `openspec/specs/alert-notifications/spec.md`
- `openspec/specs/frontend-operator-console/spec.md`

## Dependencies

Builds on the archived `modularize-alert-notification-boundary` and
`add-email-telegram-notification-adapters` changes. The implementation must
continue to use `AlertNotificationPort`, immutable alert notification snapshots,
the notification provider registry, and focused frontend notification modules.
