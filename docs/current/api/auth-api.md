# Auth API

Authentication and identity endpoints.

`identity-service` owns users, roles, refresh sessions, login audit, password
reset, and email verification. Browser traffic reaches these endpoints through
`api-gateway` under `/auth/**`; the service itself listens on port `8081`.

## Public Auth Endpoints

```http
POST /auth/register
POST /auth/login
POST /auth/refresh
POST /auth/logout
POST /auth/forgot-password
POST /auth/reset-password
POST /auth/email-verification/request
POST /auth/email-verification/confirm
```

`POST /auth/register` accepts:

```json
{
  "email": "operator@example.com",
  "password": "Str0ng!pass"
}
```

Passwords are validated at the API boundary: 8-128 characters with uppercase,
lowercase, digit, and special character. Registration returns `userId` and
`email`; it does not return tokens.

`POST /auth/login` accepts email and password and returns:

```json
{
  "accessToken": "<jwt>",
  "refreshToken": "<opaque-refresh-token>",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

Login audit captures client IP from `X-Forwarded-For`, then `X-Real-IP`, then
the servlet remote address. `User-Agent` and `X-Correlation-Id` or
`X-Request-Id` are also passed into the auth service when present.

`POST /auth/refresh` and `POST /auth/logout` accept:

```json
{
  "refreshToken": "<opaque-refresh-token>"
}
```

`POST /auth/forgot-password` and
`POST /auth/email-verification/request` accept:

```json
{
  "email": "operator@example.com"
}
```

`POST /auth/reset-password` accepts `token` and `newPassword`; the new password
uses the same complexity policy as registration. `POST
/auth/email-verification/confirm` accepts `token`.

Recovery request responses contain only the generic `password reset requested`
or `email verification requested` message, including for unknown addresses.
They never contain a usable code. Configure `SPRING_MAIL_HOST`,
`SPRING_MAIL_PORT`, `SPRING_MAIL_USERNAME`, `SPRING_MAIL_PASSWORD`, and
`APP_AUTH_MAIL_FROM` (Compose: `AUTH_MAIL_FROM`) to deliver codes to the stored
account email. SMTP authentication and STARTTLS are configured through
`SMTP_AUTH` and `SMTP_STARTTLS_ENABLE` in Compose. Configure
`AUTH_MAIL_ENCRYPTION_KEY` with a stable Base64-encoded 32-byte random key for
AES-GCM encryption of pending recovery deliveries. Keep this key in secret
storage and share it across identity replicas. Replacing it requires draining
the pending queue first; existing encrypted deliveries otherwise cannot be read.
Without a mail transport or a valid encryption key, both request endpoints return
`503` before checking whether an address exists.

The recovery token and encrypted `recovery_mail_outbox` row commit together.
An executor wakes delivery after commit; a five-second scheduler also discovers
pending deliveries after a restart or executor rejection. SMTP calls run outside
database transactions. Failed deliveries retry with exponential backoff from five
seconds to five minutes, until the recovery code expires. A processing claim can
be recovered after 120 seconds; completion requires its exact owner and attempt.
Successful delivery clears the encrypted payload; expired rows are deleted.
Only error categories are persisted, without codes or recipient addresses.
Delivery is at least once: a crash after SMTP acceptance can resend the same code.
An accepted request does not claim that an email was delivered. Refresh rotation
and one-time recovery-code consumption lock the token row until commit.

## Authenticated User Endpoints

```http
GET /auth/me
POST /auth/logout-all
POST /auth/change-password
```

These endpoints require `Authorization: Bearer <accessToken>`.

`GET /auth/me` returns the current user id, email, role, lifecycle status, and
email verification flag. `POST /auth/logout-all` revokes active refresh
sessions for the current user. `POST /auth/change-password` accepts:

```json
{
  "currentPassword": "OldPass!1",
  "newPassword": "NewPass!2"
}
```

## Admin User Endpoints

The gateway requires the `ADMIN` role for `/auth/admin/**`.

```http
GET /auth/admin/users
PATCH /auth/admin/users/{userId}/status
PATCH /auth/admin/users/{userId}/role
DELETE /auth/admin/users/{userId}
GET /auth/admin/users/{userId}/sessions
POST /auth/admin/users/{userId}/sessions/revoke
```

Status update request:

```json
{
  "status": "DISABLED"
}
```

Role update request:

```json
{
  "role": "ADMIN"
}
```

Admin user responses include `id`, `email`, `role`, `status`,
`emailVerified`, `createdAt`, and `updatedAt`. Session responses include
session id, user id, creation, expiry, revocation timestamp, and active flag.

## Token And Boundary Notes

Access tokens are bearer JWTs verified by `api-gateway` before protected routes
are forwarded. Refresh tokens are server-side session credentials and are sent
only to identity endpoints that explicitly accept `refreshToken`.

Current public auth routes are unversioned. Public API key management, MFA, SSO,
SCIM, custom RBAC, audit export, and tenant administration are not implemented;
they are target product/security gaps in
[../../target/production-readiness.md](../../target/production-readiness.md).

### User lifecycle propagation

Administrative status changes and soft deletion commit the user state, refresh
session revocation and identity lifecycle outbox event in one local transaction.
Device-service applies the existing Kafka lifecycle event asynchronously; these
endpoints do not call device-service while the identity transaction is open.
The existing reconciliation job repairs projection drift through the same outbox.
The former synchronous lifecycle-delivery toggle is no longer used.
