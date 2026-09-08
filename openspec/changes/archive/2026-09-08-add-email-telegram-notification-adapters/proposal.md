# Change Proposal: Add Email And Telegram Notification Adapters

## Why

Alert notifications currently have a provider-neutral webhook delivery path.
Operators also need direct notification channels for email and Telegram so alert
delivery can reach common incident response paths without custom webhook
bridges.

This change builds on the modular alert notification boundary and adds the first
two concrete provider adapters beyond webhook.

## What Changes

- Add an `EMAIL` provider adapter for alert notification delivery.
- Add a `TELEGRAM` provider adapter for alert notification delivery.
- Validate non-secret channel config for both channel types.
- Resolve provider credentials only through `credentialsRef` or configured
  service-level secrets, never through channel config JSON.
- Normalize provider responses into existing notification attempt states.
- Keep retry, lease, idempotency, preference, and quiet-hours behavior in the
  notification application layer.
- Add user-facing frontend configuration for email and Telegram channels.
- Keep account notification preferences configurable from the frontend,
  including severities, channel types, device scope, quiet hours, timezone, and
  critical quiet-hours bypass.
- Document API config examples and operational environment variables.

## Non-Goals

- Do not create a separate notification-service.
- Do not add Kafka notification request topics.
- Do not implement SMS, push, Slack, or generic messenger adapters in this
  change.
- Do not store SMTP passwords, Telegram bot tokens, or API keys in channel
  config.
- Do not expose raw provider secrets in frontend forms.
- Do not add per-user notification preferences beyond the existing account-level
  preference behavior.

## Impacted Areas

- `backend/services/alert-service/src/main/java/com/voltcanary/alert/**`
- `backend/services/alert-service/src/main/resources/application*.yml`
- `backend/services/alert-service/src/test/java/com/voltcanary/alert/**`
- `frontend/src/alerts/alertsApi.ts`
- `frontend/src/pages/SettingsPage.tsx`
- `frontend/src/pages/AlertsPage.tsx`
- `frontend/src/features/**`
- `frontend/src/**/*.test.tsx`
- `docs/current/api/alerts-api.md`
- `docs/current/architecture/10-consistency-contracts.md`
- `docs/current/flows/**`
- `openspec/specs/alert-notifications/spec.md`
- `openspec/specs/frontend-operator-console/spec.md`

## Dependencies

Implement after, or together with, `modularize-alert-notification-boundary` so
email and Telegram delivery are added as provider adapters rather than direct
branches inside the delivery worker.
