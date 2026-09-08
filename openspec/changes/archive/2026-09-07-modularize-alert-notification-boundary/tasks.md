# Tasks: Modularize Alert Notification Boundary

## 1. Notification Boundary

- [x] 1.1 Add an `AlertNotificationPort` boundary used by alert lifecycle and escalation code.
- [x] 1.2 Introduce an immutable alert notification request snapshot and stop passing JPA `Alert` entities as the notification boundary contract.
- [x] 1.3 Keep alert creation, deduplication, suppression, escalation, and alert event audit outside provider delivery code.

## 2. Notification Module Structure

- [x] 2.1 Organize notification domain, application, provider, and controller code under a clear package boundary.
- [x] 2.2 Keep notification channel, preference, attempt, lease, retry, and retention behavior inside the notification module.
- [x] 2.3 Preserve the existing `/alerts/notification-*` and `/alerts/{id}/notification-attempts` route behavior.

## 3. Frontend Configuration Boundary

- [x] 3.1 Keep notification channel and preference configuration user-facing in the frontend.
- [x] 3.2 Keep notification API access in `frontend/src/alerts/alertsApi.ts` with typed request/response models.
- [x] 3.3 Extract or prepare focused notification settings components/hooks before adding provider-specific channel forms to large route pages.
- [x] 3.4 Preserve alert notification attempt visibility from the alert investigation UI.

## 4. Provider Adapter Boundary

- [x] 4.1 Add a provider adapter interface and registry keyed by notification channel type.
- [x] 4.2 Move existing webhook delivery behind the provider adapter boundary.
- [x] 4.3 Ensure provider adapters return normalized delivery results without owning retry or attempt state transitions.

## 5. Docs And Verification

- [x] 5.1 Update current API, architecture, consistency, and event docs to describe the alert-service notification module boundary and frontend configuration ownership.
- [x] 5.2 Add or update focused alert-service tests for notification idempotency, after-commit processing, route compatibility, and webhook delivery through the adapter registry.
- [x] 5.3 Add or update focused frontend tests for notification settings route compatibility when UI code is touched.
- [x] 5.4 Run `cd backend; mvn -pl services/alert-service -am test`.
- [x] 5.5 Run `cd frontend; npm run typecheck; npm run test; npm run build` when frontend code is touched.
- [x] 5.6 Run `npm run openspec:validate`.
