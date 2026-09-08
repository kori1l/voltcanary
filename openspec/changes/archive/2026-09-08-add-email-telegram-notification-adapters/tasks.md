# Tasks: Add Email And Telegram Notification Adapters

## 1. Email Adapter

- [x] 1.1 Add an `EMAIL` provider adapter behind the notification provider registry.
- [x] 1.2 Add email channel config validation for recipients, optional cc/bcc, aliases, and subject prefix.
- [x] 1.3 Wire email provider credentials through `credentialsRef` or service-level configuration, not channel config JSON.
- [x] 1.4 Map successful, retryable, and permanent email provider outcomes into normalized delivery results.

## 2. Telegram Adapter

- [x] 2.1 Add a `TELEGRAM` provider adapter behind the notification provider registry.
- [x] 2.2 Add Telegram channel config validation for `chatId`, optional parse mode, and optional preview flag.
- [x] 2.3 Wire Telegram bot credentials through `credentialsRef` or service-level configuration, not channel config JSON.
- [x] 2.4 Map successful, retryable, and permanent Telegram API outcomes into normalized delivery results.

## 3. Shared Message And Delivery Behavior

- [x] 3.1 Reuse the canonical notification message factory for email and Telegram content.
- [x] 3.2 Preserve notification preferences, quiet hours, idempotency, attempt leases, retries, and retention behavior.
- [x] 3.3 Ensure metrics and logs include email and Telegram delivery outcomes without exposing secrets.

## 4. Frontend Configuration

- [x] 4.1 Add user-facing channel configuration for `EMAIL` and `TELEGRAM`.
- [x] 4.2 Keep shared channel fields consistent across webhook, email, and Telegram forms.
- [x] 4.3 Add email form controls for recipients, optional cc/bcc, from alias, subject prefix, enabled state, and credentials reference when needed.
- [x] 4.4 Add Telegram form controls for chat id, optional parse mode, web preview toggle, enabled state, and credentials reference when needed.
- [x] 4.5 Keep notification preferences editable from the frontend, including enabled state, severities, channel type filters, device scope, quiet hours, timezone, and critical quiet-hours bypass.
- [x] 4.6 Prevent frontend forms from collecting or submitting provider secrets in channel config JSON.
- [x] 4.7 Keep notification API calls typed in `frontend/src/alerts/alertsApi.ts` and add focused tests for provider-specific form payloads.

## 5. Docs And Verification

- [x] 5.1 Update alerts API docs with email and Telegram channel config examples, frontend configuration expectations, and failure behavior.
- [x] 5.2 Update verification flows for email and Telegram notification delivery.
- [x] 5.3 Add focused backend tests for config validation, secret rejection, provider success, retryable failure, and permanent failure.
- [x] 5.4 Add focused frontend tests for email channel creation, Telegram channel creation, preference editing, and secret-field rejection.
- [x] 5.5 Run `cd backend; mvn -pl services/alert-service -am test`.
- [x] 5.6 Run `cd frontend; npm run typecheck; npm run test; npm run build`.
- [x] 5.7 Run `npm run openspec:validate`.
