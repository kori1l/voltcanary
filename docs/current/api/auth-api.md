# Auth API

Authentication and identity endpoints.

`identity-service` owns users, roles, refresh sessions, login audit, password
reset, and email verification. Browser traffic reaches these endpoints through
`api-gateway` under `/auth/**`; the service itself listens on port `8081`.

## Public Auth Endpoints

The web console uses browser sessions:

```http
POST /auth/browser/{sessionId}/login
POST /auth/browser/{sessionId}/refresh
POST /auth/browser/{sessionId}/logout
```

`sessionId` is a client-generated UUID and is not a credential. Login accepts
the normal email/password body. Responses contain `sessionId`, `accessToken`,
`tokenType`, and `expiresIn`; refresh tokens never appear in browser JSON.
The refresh credential is rotated through a host-only `HttpOnly; SameSite=Strict`
cookie scoped to `/auth/browser/{sessionId}`. Separate paths prevent a late
response from an old login overwriting another login's cookie. Login and refresh
responses use `Cache-Control: no-store`.

Every browser-session mutation requires an exact allowed `Origin`; missing and
untrusted origins receive 403 before credentials are consumed. Configure
`app.auth.browser.allowed-origins` for the console's actual origin, in addition
to the gateway CORS allowlist. Fetch requests use `credentials: include`.
Cookies are Secure by default. The local profile permits HTTP development with
`BROWSER_SESSION_SECURE_COOKIE=false`; TLS deployments set it to true.

The console retains the short-lived access token and public session/rotation IDs
in browser storage. It removes the former `voltcanary.refreshToken` entry during
upgrade; an old login may require signing in again. Refresh remains serialized
across tabs, and logout revokes the server-side refresh session and expires its
path-scoped cookie.

Programmatic clients use the JSON credential endpoints below. Their refresh
credentials must be stored by those clients securely; the web console does not
use this transport.

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

The gateway requires the `ADMIN` role for `/auth/admin/**`. Identity-service also
verifies the bearer token and current persisted ACTIVE status and ADMIN role on
every admin operation, so an old token does not retain revoked admin authority.
Role and status changes revoke refresh sessions. User writes use an optimistic
revision; competing updates return 409 `concurrent_update`.

`GET /auth/admin/users` accepts `page` (zero-based, default 0), `size` (default 100,
range 1..200), and `search` (email, role or status). Invalid pagination returns 400. Filtering, literal wildcard escaping, and `createdAt DESC, id DESC` ordering
execute in the database; the response remains an array.

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
are forwarded. Refresh tokens are server-side session credentials. The console
sends them only as HttpOnly cookies to browser-session endpoints; programmatic
clients use the JSON `refreshToken` endpoints described above.

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

### Registration and login concurrency

Registration normalizes email and relies on the database unique constraint for
concurrent duplicates. A duplicate email returns 409 `duplicate_email`; unrelated
database errors are not translated to duplicate email. Login admission and
failure windows are persisted in shared bounded database counters across identity
replicas and restarts. Keys are hashed; expiry pruning and capacity checks execute
under a short database guard transaction. `app.auth.login.tracked-key-limit` defaults
to 20,000. Password verification runs outside the session issuance transaction;
issuance reloads the user and verifies ACTIVE status and the authenticated user
revision before committing tokens. API error codes preserve lowercase spelling
in the console.
