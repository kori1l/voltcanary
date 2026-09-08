# Change Proposal: Modularize Alert Notification Boundary

## Why

Notification delivery currently lives inside `alert-service`, which is
appropriate for the current expected load. The coupling is still too direct:
alert lifecycle code calls notification delivery code with alert entities, and
provider delivery behavior sits close to alert ownership concerns.

VoltCanary needs notification delivery to remain inside `alert-service` for now
while making the boundary extractable later. The next notification work will add
provider-specific adapters such as email and Telegram, so the module needs a
clear application boundary before adding more channels.

## What Changes

- Introduce an explicit alert-to-notification port inside `alert-service`.
- Pass immutable alert notification snapshots across that port rather than JPA
  alert entities.
- Organize notification code around domain, application, provider adapter, and
  controller responsibilities.
- Add a provider adapter interface and registry so channel-specific transports
  are selected by `NotificationChannelType`.
- Keep durable attempts, leases, retries, idempotency, preferences, and channel
  management owned by the notification module.
- Keep alert creation, deduplication, escalation, suppression, and alert event
  audit owned by alert lifecycle code.
- Keep the current public API routes compatible during this refactor.
- Keep user-facing notification configuration available through the frontend
  while routing the implementation through focused notification frontend
  modules and typed API helpers.

## Non-Goals

- Do not create a separate `notification-service` in this change.
- Do not add Kafka notification request topics in this change.
- Do not change the existing alert lifecycle semantics.
- Do not add new provider channels beyond the existing webhook behavior in this
  boundary change.
- Do not redesign the notification settings UI beyond the extraction needed to
  keep future channel forms isolated.

## Impacted Areas

- `backend/services/alert-service/src/main/java/com/voltcanary/alert/**`
- `backend/services/alert-service/src/main/resources/db/migration/**`
- `frontend/src/alerts/alertsApi.ts`
- `frontend/src/pages/SettingsPage.tsx`
- `frontend/src/pages/AlertsPage.tsx`
- `frontend/src/features/**`
- `docs/current/api/alerts-api.md`
- `docs/current/architecture/02-service-boundaries.md`
- `docs/current/architecture/10-consistency-contracts.md`
- `docs/current/events/alert-events.md`
- `openspec/specs/backend-architecture/spec.md`
