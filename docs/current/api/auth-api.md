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
