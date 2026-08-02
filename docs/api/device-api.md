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

When an account is synchronized as `DELETED`, device-service applies a soft
cleanup policy. Account-owned devices are marked `DECOMMISSIONED`, owned data
sources are disabled and marked `REVOKED`, and any active source token gets
`token_revoked_at`. Locations, sensors, and analytics configs remain as
historical registry records behind the decommissioned devices.

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

## Maintenance Windows

Device-service is the source of truth for planned maintenance windows. Windows
belong to one account and one device and are rejected for decommissioned
devices. Telemetry remains immutable; maintenance policy is interpreted by
downstream services through events and reconciliation APIs.

Maintenance operations are the user-facing aggregate for calendar planning.
One operation can target multiple devices; device-service creates one
per-device maintenance window for each affected device so alert, analytics, and
ML services can continue to reason on device-local intervals.

Public endpoints:

- `GET /maintenance-operations`: list account-level operations for the calendar.
  Optional filters: `from`, `to`, repeated `deviceIds`, `locationId`, `status`,
  `includeCancelled`, `limit`, and `offset`.
- `POST /maintenance-operations`: create an operation for one or more devices.
- `GET /maintenance-operations/{operationId}`: read one operation.
- `PATCH /maintenance-operations/{operationId}`: replace operation details and
  target device set. Removed devices have their unfinished child windows
  cancelled; newly added devices get child windows.
- `POST /maintenance-operations/{operationId}/cancel`: cancel an operation and
  all unfinished child windows.
- `POST /maintenance-operations/{operationId}/finish-now`: end an active
  operation and all active child windows at the current time.
- `GET /maintenance-operations/{operationId}/outcomes`: list structured
  outcomes recorded against an operation.
- `POST /maintenance-operations/{operationId}/outcomes`: record an outcome for
  an operation device/window.
- `GET /maintenance-outcomes/{outcomeId}`: read one outcome.
- `PATCH /maintenance-outcomes/{outcomeId}`: update structured outcome fields.
- `GET /devices/{deviceId}/maintenance-windows`: list maintenance windows for
  an owned device. Optional filters: `status`, `from`, `to`, `page`, and
  `size`.
- `POST /devices/{deviceId}/maintenance-windows`: create a window.
- `GET /devices/{deviceId}/maintenance-windows/{windowId}`: read one window.
- `PATCH /devices/{deviceId}/maintenance-windows/{windowId}`: update title,
  description, time range, and policy flags.
- `POST /devices/{deviceId}/maintenance-windows/{windowId}/cancel`: cancel a
  scheduled or active window.
- `POST /devices/{deviceId}/maintenance-windows/{windowId}/finish-now`: end an
  active window at the current time.

Create/update request:

Operation request:

```json
{
  "title": "Firmware upgrade",
  "description": "Batch maintenance for kitchen devices.",
  "deviceIds": [
    "0b3ac2da-ea4e-4c65-a7ae-433cbec33420",
    "68ec0d68-c9c9-4993-b527-efe2a67f965d"
  ],
  "startAt": "2026-06-30T08:00:00Z",
  "endAt": "2026-06-30T09:00:00Z",
  "suppressAlerts": true,
  "excludeFromAnalytics": true,
  "excludeFromMl": true
}
```

Operation responses include `id`, account, title, time range, status, policy
flags, audit fields, `version`, and a `devices` array with device id/name/type,
location id/name, child `windowId`, and per-device status.

Outcome request:

```json
{
  "deviceId": "0b3ac2da-ea4e-4c65-a7ae-433cbec33420",
  "maintenanceWindowId": "c46b14cf-6399-4fa6-a8bc-ae95f7d7e7ed",
  "observedAt": "2026-06-30T08:30:00Z",
  "resolvedAt": "2026-06-30T09:15:00Z",
  "outcomeType": "CONFIRMED_FAILURE",
  "failureMode": "COMPRESSOR_OVERHEAT",
  "component": "compressor",
  "severity": "HIGH",
  "downtimeMinutes": 45,
  "technicianNotes": "Compressor replaced.",
  "source": "TECHNICIAN",
  "confidence": 0.95
}
```

Outcome types are `CONFIRMED_FAILURE`, `CORRECTIVE_MAINTENANCE`,
`PREVENTIVE_MAINTENANCE`, `INSPECTION_NORMAL`, `FALSE_POSITIVE`,
`SENSOR_FAULT`, `UNRESOLVED_ANOMALY`, and `UNKNOWN`. Responses include account,
device, operation/window ids, structured outcome fields, audit actor fields,
timestamps, and monotonic `version`.

Device-scoped window request:

```json
{
  "title": "Firmware upgrade",
  "description": "Expected telemetry gap while replacing the power module.",
  "startAt": "2026-06-30T08:00:00Z",
  "endAt": "2026-06-30T09:00:00Z",
  "suppressAlerts": true,
  "excludeFromAnalytics": true,
  "excludeFromMl": true
}
```

Response fields include `id`, `accountId`, `deviceId`, `status`, policy flags,
`cancelledAt`, `cancellationReason`, audit actor fields, timestamps, and
monotonic `version`.

Internal reconciliation endpoints:

- `GET /internal/devices/{deviceId}/maintenance-windows`
- `GET /internal/maintenance-windows/changes?updatedAfter={instant}&limit=100`
- `GET /internal/maintenance-outcomes/changes?updatedAfter={instant}&limit=100`

Every create/update/cancel/finish operation writes an outbox event to
`maintenance-window.changed.v1` with the full window snapshot and version.

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
