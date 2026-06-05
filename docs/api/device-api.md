# Device API

Device registration, inventory, and lifecycle endpoints.

Public endpoints are expected to be reached through the API Gateway with a user
access token. The gateway validates the token, strips spoofed user headers, and
forwards `X-User-Id`, `X-User-Email`, and `X-User-Role` to `device-service`.
For direct local service access, `device-service` can still validate a bearer
token through the shared `common-security` JWT contract. Internal endpoints
require `X-Internal-Service-Token`.

## Accounts

- `GET /accounts/current`: returns or lazily creates the current user's device
  account.

The API Gateway routes `/accounts/**` to `device-service`.

Accounts have lifecycle status values `ACTIVE`, `DISABLED`, and `DELETED`.
Disabled or deleted accounts receive `403 account_inactive` when accessing
public device APIs. Identity-service can synchronize lifecycle state through the
internal endpoint:

- `POST /internal/accounts/users/{userId}/lifecycle`

Request:

```json
{
  "email": "user@example.com",
  "status": "DISABLED"
}
```

## Devices And Ownership

- `GET /devices`: list devices for the current account. Optional query
  parameters: `locationId`, `type`, `status`, `search`, `limit`, and `offset`.
  Device create/update accepts optional `metadata` for vendor-specific fields.
  Metadata is stored as JSON and must not contain obvious credential keys such
  as `password`, `token`, `secret`, or `credential`.
- `POST /devices/imports/csv`: import devices from `text/plain` CSV. The header
  must be `name,type,locationId,manufacturer,model`. All rows are validated
  before any device is created.
- `GET /devices/export.csv`: export the current account inventory as CSV with
  `id,name,type,locationId,manufacturer,model,status`.

Device, location, and data-source APIs are scoped to the authenticated user's
account. Other services can verify ownership through:

- `GET /internal/devices/{deviceId}/access?userId={userId}`
- `GET /internal/devices/{deviceId}/owner`

Response:

```json
{ "allowed": true }
```

Owner lookup response:

```json
{
  "deviceId": "11a45242-7bf4-4f88-a71c-5969299f3bfc",
  "accountId": "2c07eb26-e038-4565-b637-d9787c861477"
}
```

Device status is controlled by enum values: `ACTIVE`, `DISABLED`, `OFFLINE`,
and `DECOMMISSIONED`.

- `POST /devices/{id}/enable`: set an owned device to `ACTIVE`.
- `POST /devices/{id}/disable`: set an owned device to `DISABLED`.
- `POST /devices/{id}/decommission`: archive an owned device as
  `DECOMMISSIONED`.
- `DELETE /devices/{id}`: archives the device as `DECOMMISSIONED`; it does not
  hard-delete historical telemetry dependencies.

Internal source existence checks return `false` for non-active devices, so
disabled or decommissioned devices cannot ingest new telemetry through the
normal source validation path.

## Data Sources

- `GET /devices/{deviceId}/data-sources`: list data sources for an owned
  device.
- `GET /devices/{deviceId}/data-sources/{dataSourceId}`: read one data source
  for an owned device.
- `POST /devices/{deviceId}/data-sources`: create a data source for an owned
  device.
- `POST /devices/{deviceId}/data-sources/imports/csv`: import data sources
  from `text/plain` CSV. The header must be `externalId,sourceType,protocol`.
  All rows are validated before any source is created.
- `GET /devices/{deviceId}/data-sources/export.csv`: export data sources for an
  owned device as CSV with `id,deviceId,externalId,sourceType,protocol,status`.
- `PATCH /devices/{deviceId}/data-sources/{dataSourceId}`: update source
  metadata.
- `DELETE /devices/{deviceId}/data-sources/{dataSourceId}`: disable the source
  and revoke its ingestion token reference. Historical telemetry remains
  intact.
- `POST /devices/{deviceId}/data-sources/{dataSourceId}/token`: rotate and
  return a new source ingestion token.
- `POST /devices/{deviceId}/data-sources/{dataSourceId}/token/revoke`: revoke
  the source ingestion token and mark the source revoked.

The database stores explicit `source_id`, `source_type`, `protocol`,
`connection_ref`, `enabled`, `status`, `last_seen_at`, and `credentials_ref`
fields. `source_id` is unique per device.

`externalId` is normalized by trimming whitespace and must be at most 128
characters using only letters, numbers, `.`, `_`, `:`, and `-`.

Only token hashes are persisted. The raw token is returned once during rotation.

Internal source lookup endpoints:

- `GET /internal/devices/{deviceId}/data-sources/{sourceId}/exists`
- `GET /internal/data-sources/by-token-prefix/{tokenPrefix}`
- `GET /internal/data-sources/by-external-id/{externalId}`
- `GET /internal/data-sources/{dataSourceId}`
- `POST /internal/data-sources/{dataSourceId}/token-used`

## Analytics Config

- `GET /devices/{deviceId}/analytics-config`: returns stored config or a default
  enabled config.
- `PATCH /devices/{deviceId}/analytics-config`: updates config for an owned
  device.

Example:

```json
{
  "enabled": true,
  "config": {
    "baselineWindowDays": 14
  }
}
```

## Sensors

- `GET /devices/{deviceId}/sensors`: list sensors for an owned device.
- `GET /devices/{deviceId}/sensors/{sensorId}`: read one sensor.
- `POST /devices/{deviceId}/sensors`: create a sensor with `name`, `type`,
  optional `unit`, optional `metricMapping`, and optional `metadata`.
- `PATCH /devices/{deviceId}/sensors/{sensorId}`: update sensor metadata.
- `DELETE /devices/{deviceId}/sensors/{sensorId}`: delete a sensor owned by the
  current account.

## Locations

- `GET /locations`: list locations for the current account. Optional query
  parameters: `search`, `limit`, and `offset`.
- Location create/update supports optional `parentId`, `building`, `floor`,
  `room`, `zone`, and `tags`.

Parent locations must belong to the current account, and a location cannot be
set as its own parent. Location search also matches building, floor, room, and
zone fields.

Example:

```json
{
  "parentId": "11111111-1111-1111-1111-111111111111",
  "name": "Rack A",
  "description": "Server room rack",
  "building": "HQ",
  "floor": "2",
  "room": "204",
  "zone": "north",
  "tags": ["critical", "it"]
}
```

## Validation

Device and location names are trimmed before storage. Device `manufacturer` and
`model` are limited to 255 characters. Invalid request bodies return `400` with
the existing `validation_failed` or `bad_request` error response shape.

## Metrics

Device-service exposes operational metrics through actuator/prometheus:

- `voltcanary.device.devices.count`, tagged by `status`.
- `voltcanary.device.devices.by_type.count`, tagged by seeded device `type`.
- `voltcanary.device.data_sources.by_protocol.count`, tagged by `protocol`.
- `voltcanary.device.data_sources.count`, tagged by source `status`.
- `voltcanary.device.internal_validation.latency`, tagged by `outcome`.
- `voltcanary.device.ownership.rejected`.
- `voltcanary.device.operation.rejected`, tagged by `reason`.

## Audit And OpenAPI

Device-service writes structured audit logs for device, location, data-source,
sensor, and analytics-config mutations. Audit entries include user id, account
id, action, target type, target id, and `X-Correlation-Id` when present. Source
tokens and other secrets are not logged.

OpenAPI is exposed by springdoc at `/v3/api-docs` and Swagger UI at
`/swagger-ui.html`. Public APIs use bearer authentication through the gateway;
internal endpoints require `X-Internal-Service-Token`.
