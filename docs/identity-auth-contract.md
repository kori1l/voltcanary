# Identity Auth Contract

This contract describes the current identity-service and API Gateway auth surface.

## Roles

- `USER`: regular application user.
- `SUPPORT`: support/operator user. Route-level use is reserved for later service-specific checks.
- `ADMIN`: identity administrator. Required for `/auth/admin/**`.

Resource ownership checks remain the responsibility of downstream services. The gateway authenticates access tokens and forwards identity headers.

## Public Endpoints

- `POST /auth/register`
- `POST /auth/login`
- `POST /auth/refresh`
- `POST /auth/logout`
- `POST /auth/forgot-password`
- `POST /auth/reset-password`
- `POST /auth/email-verification/request`
- `POST /auth/email-verification/confirm`

`/auth/logout` validates and revokes the supplied refresh token. `/auth/refresh` rotates refresh tokens and rejects reuse.

## Authenticated User Endpoints

- `GET /auth/me`
- `POST /auth/logout-all`
- `POST /auth/change-password`

These endpoints require `Authorization: Bearer <accessToken>`.

## Admin Endpoints

All admin endpoints require `Authorization: Bearer <accessToken>` for a user with role `ADMIN`.

- `GET /auth/admin/users`
- `PATCH /auth/admin/users/{userId}/status`
- `PATCH /auth/admin/users/{userId}/role`
- `GET /auth/admin/users/{userId}/sessions`
- `POST /auth/admin/users/{userId}/sessions/revoke`

Supported statuses are `ACTIVE`, `DISABLED`, `LOCKED`, and `PENDING_VERIFICATION`.

## Token Contract

Access tokens are JWTs signed with `RS256`.

Required JWT values:

- Header `kid`
- `iss=voltcanary-identity`
- `aud=voltcanary-api`
- `jti`
- `typ=access`
- `sub`
- `email`
- `role`
- `iat`
- `exp`

Identity signs tokens with the configured private key. API Gateway and shared validators verify using configured public keys in `kid=base64;kid2=base64` format.

Default TTLs:

- Access token: `900` seconds
- Refresh token: `2592000` seconds
- Password reset token: `900` seconds
- Email verification token: `86400` seconds

## Gateway Headers

For protected routes the gateway removes incoming client identity headers and sets these values from the validated access token:

- `X-User-Id`
- `X-User-Email`
- `X-User-Role`

## Error Shape

Identity-service generated errors use:

```json
{
  "code": "authentication_failed",
  "message": "Invalid email or password",
  "path": "/auth/login",
  "correlationId": "request-correlation-id",
  "timestamp": "2026-05-24T00:00:00Z"
}
```

Public login and token failures keep messages generic. Detailed failure reasons are kept in login audit records.
