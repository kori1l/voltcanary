# Tasks: Harden Notification Delivery Operations

## 1. Backend Test Delivery

- [ ] 1.1 Add a `POST /alerts/notification-channels/{id}/test` route scoped like existing channel management.
- [ ] 1.2 Create test notification attempts without creating alerts or alert lifecycle events.
- [ ] 1.3 Reuse provider registry, canonical notification message creation, credentials resolution, metrics, and normalized delivery result handling.
- [ ] 1.4 Reject secret-like keys in test-delivery request bodies.

## 2. Retry And Attempt Diagnostics

- [ ] 2.1 Add bounded retry/backoff configuration for initial delay, multiplier, max delay, jitter, and max attempts.
- [ ] 2.2 Persist or expose next retry timestamp, retry exhaustion, and max-attempt context for attempts.
- [ ] 2.3 Preserve compatibility for existing attempts and current fixed-delay defaults.
- [ ] 2.4 Add focused tests for backoff, jitter bounds, retry exhaustion, and stale lease replay.

## 3. Channel Diagnostics API

- [ ] 3.1 Add `GET /alerts/notification-channels/{id}/diagnostics`.
- [ ] 3.2 Summarize adapter registration, enabled state, last success/failure, retrying/failed/pending counts, oldest due age, and last test attempt.
- [ ] 3.3 Redact provider response summaries and keep diagnostics read-only.
- [ ] 3.4 Add tests for account scoping, unsupported adapters, disabled channels, and redacted failures.

## 4. Frontend Notification Operations

- [ ] 4.1 Add typed alerts API helpers for channel diagnostics and test delivery.
- [ ] 4.2 Add focused notification feature components/hooks for diagnostics loading, test send, and result states.
- [ ] 4.3 Keep `SettingsPage.tsx` as composition and preserve existing webhook, email, Telegram, and preference forms.
- [ ] 4.4 Add frontend tests for diagnostics states, test-send action, retry/failure rendering, and secret-field rejection.

## 5. Docs And Verification

- [ ] 5.1 Update `docs/current/api/alerts-api.md` with implemented test-delivery and diagnostics contracts.
- [ ] 5.2 Update `docs/current/architecture/10-consistency-contracts.md` with retry/backoff and diagnostics behavior.
- [ ] 5.3 Update `docs/current/flows/notification-delivery-verification.md` with test delivery, diagnostics, and retry evidence.
- [ ] 5.4 Run `cd backend; mvn -pl services/alert-service -am test`.
- [ ] 5.5 Run `cd frontend; npm run typecheck; npm run test; npm run build`.
- [ ] 5.6 Run `npm run openspec:validate`.
