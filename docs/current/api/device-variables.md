# Device variable API

The public gateway retains the existing `/device-types`, `/devices`, `/telemetry`
and `/alert-rules` service routing. Use the normal authenticated owner session.
Definitions and mutations are account-owned; workspace read access does not grant
permission to publish formulas or mutate bindings. Internal routes require
`X-Internal-Service-Token` and must not be exposed as public capabilities.

## Definitions and binding

Base: `/device-types/{code}/variables` in device-service.

| Method/path | Contract |
| --- | --- |
| GET base | List definitions for the owner's account/type |
| POST base | Create definition and draft revision |
| GET `/{id}` | Definition plus immutable revisions |
| POST `/{id}/revisions` | New draft, `expectedVersion` required |
| POST `/{id}/publish` | `revision`, `expectedVersion`, `makeDefault` |
| POST `/{id}/archive` | `expectedVersion`; preserve history |
| PUT `/{id}/default-binding` | Explicit source role and alias/path mapping |
| POST `/{id}/inheritance/reconcile` | Bounded reconciliation and per-device outcomes |
| GET/POST `/{id}/rollouts` | Progress / bounded explicit target requests |
| POST `/{id}/rollouts/{targetId}/resume` | Resume one pending/failed target |

Drafts contain `key`, `label`, `origin`, `unit`, `inputs`, `expression`, optional
`minimum`, `maximum`, and `expectedVersion` for edits. Each input declares its
unit and optional bounds. Expressions are typed AST nodes, not executable source:
`{op:"INPUT",alias:"x"}`, `{op:"CONSTANT",value:0.1}` and binary nodes with
`left`/`right`. Output identity, origin and unit are immutable across revisions.

`GET/PUT /devices/{deviceId}/data-sources/{sourceId}/variable-bindings` reads or
updates a binding. PUT accepts `variableId`, published `revision`,
`expectedVersion` (0 for new), `inputPaths` and `enabled`.
`GET .../variable-bindings/status` exposes desired generation and bindings.
Conflicting versions or retained-input dependency changes return 409. Invalid
formulas/units return 400; activation while its flag is disabled returns 503.

## Preview and telemetry

`POST /telemetry/variables/preview` accepts `deviceId`, `dataSourceId`, `contract`
and explicit numeric `inputs`. It authorizes the device/source and uses the live
evaluator without persisting values. The result contains status, optional value
and evaluation steps. The console invalidates preview after edits and requires a
fresh successful result before publication.

Under `/telemetry/devices/{deviceId}`:

- `GET /variables`: source-bound descriptors with UUID, key, label, unit, origin,
  revision, generation, current enabled capability and storage token.
- `GET /latest?variableId=...&sourceId=...`: latest valid sample by event time.
- `GET /readings` and `/readings/export`: require variableId, sourceId, from, to;
  optional limit (maximum 2,000), timestamp cursor, bucketSeconds and reducer
  AVG/MIN/MAX. Maximum range is 31 days. Responses are JSON series; console
  CSV export pages raw values with a 20,000-sample browser bound.
- `GET /variables/evaluations?sourceId=...`: last 100 source evaluations.

Do not combine legacy `metric` with `variableId`. Points expose timestamp, value,
revision, generation, count and extrema. Raw zero is valid. Missing data is empty,
not zero. Legacy metric endpoints retain their previous contracts.

Dashboard time-series selectors include `metric` (descriptor token), `variableId`
and `dataSourceId`. The console variable picker currently exists in private owner
dashboards; workspace variable-picker parity remains tracked release work.

## Threshold rules

Existing `/alert-rules` create/update/test accepts a DEVICE threshold rule with
the descriptor token as `metric`, matching `thresholdUnit`, and
`config.variable={variableId,dataSourceId,unit,maxLiveAgeSeconds}`. ABOVE_THRESHOLD
and BELOW_THRESHOLD are supported. Age defaults to 600 seconds, bounded 1–86,400.
Public requests resolve the actual account from authenticated user context;
the console does not submit an account header. Gateway removal of caller-supplied
account headers cannot redirect rule ownership to the user UUID.
Updates include `config.expectedVersion`; stale edits return 409. Re-enabling
checks current capability. Historical dry runs use full-resolution samples and
emit no notifications; they report insufficient data explicitly.

## Internal recovery

`GET /internal/source-processing-bundles` pages immutable snapshots by `after`
and `limit`, with optional sourceId/generation lookup. Device-service owns the
cursor. Ingestion recovery is per replica; telemetry persists its shared cursor.
`GET/POST /internal/device-variable-runtime-access` checks fresh capability;
batch POST is bounded to 500 identities. Credentials never appear in bundles.
