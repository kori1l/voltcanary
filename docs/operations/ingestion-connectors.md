# Ingestion Connectors

Active connector sources run inside `ingestion-service` after local lease
ownership is acquired. HTTP polling, Modbus TCP, and OPC UA connector failures
are isolated per source; one failing source must not stop ingestion for other
sources.

Connector ownership is fenced. Redis-backed leases store owner identity plus a
generation, and in-memory local leases use the same contract. Renew, release,
polling publication, and subscription forwarding must use the current
generation. Command-capable adapters remain disabled until their command
boundary can require current fenced ownership.

## Modbus TCP Source Configuration

Use `adapterType=GENERIC_MODBUS_TCP`, `protocol=MODBUS_TCP`, `transport=TCP`,
`adapterConfigSchemaVersion=v1`, and `mappingSchemaVersion=v1`.

Adapter config:

```json
{
  "host": "plc-1.internal",
  "port": 502,
  "unitId": 1,
  "pollingInterval": "5s",
  "timeout": "2s",
  "timestampPolicy": "POLL_TIME"
}
```

Register mapping:

```json
{
  "registers": [
    {
      "metric": "activePowerW",
      "function": "HOLDING_REGISTER",
      "address": 0,
      "dataType": "FLOAT32",
      "scale": 1,
      "offset": 0,
      "unit": "W"
    }
  ]
}
```

Store protocol credentials only in `credentialReferences`, for example
`credentialReferences.basicAuth=env:MODBUS_BASIC_AUTH`. Do not put passwords,
tokens, or private secret material in `mapping` or `adapterConfig`.

## OPC UA Source Configuration

Use `adapterType=GENERIC_OPC_UA`, `protocol=OPC_UA`, `transport=TCP`,
`adapterConfigSchemaVersion=v1`, and `mappingSchemaVersion=v1`.

Adapter config:

```json
{
  "endpointUrl": "opc.tcp://plc-1.internal:4840",
  "securityPolicy": "Basic256Sha256",
  "authenticationMode": "USERNAME",
  "sessionTimeout": "30s",
  "publishingInterval": "1s",
  "samplingInterval": "250ms",
  "queueSize": 10,
  "timestampPolicy": "SOURCE_TIME"
}
```

Node mapping:

```json
{
  "nodes": [
    {
      "metric": "activePowerW",
      "nodeId": "ns=2;s=Line1.Power",
      "dataType": "DOUBLE",
      "scale": 1,
      "offset": 0,
      "unit": "W"
    }
  ]
}
```

`authenticationMode=USERNAME` requires `credentialReferences.username` and
`credentialReferences.password`. `authenticationMode=CERTIFICATE` requires
`credentialReferences.clientCertificate` and `credentialReferences.privateKey`;
`credentialReferences.trustList` may reference trusted server certificates.
Store only references such as `env:OPCUA_USER`, `env:OPCUA_PASSWORD`, or an
internal secret reference. Do not store raw usernames, passwords, private keys,
or certificates in source config.

## Failure Modes

Invalid node mappings fail before a session is opened and are reported in
per-source connector health. Endpoint failures, session loss, authentication
failure, and certificate/trust failures move the source through connector
backoff and reconnect without affecting other sources.

Lease loss, source disable, and config replacement close the local OPC UA
session immediately. After lease loss, the replica that lost ownership must
stop emitting telemetry for that source; a future owner starts a new session
from the latest runtime config.

## Verification

Run the ingestion-service module tests after connector changes:

```powershell
cd backend
mvn -pl services/ingestion-service -am test
```

Run OpenSpec validation before closing the active change:

```powershell
npm run openspec:validate
```

## Horizontal Ingestion Smoke

Use the horizontal scale smoke before enabling or changing multi-replica
ingestion deployments:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ingestion-horizontal-scale-smoke.ps1 -StartStack -CiMode -TimeoutSeconds 600
```

The smoke starts two local `ingestion-service` replicas on host ports `18083`
and `18084`, shares Redis and Redpanda, publishes source runtime config, and
writes a concise JSON summary to
`artifacts/ingestion-horizontal-scale-summary.json`. Generated `artifacts/`
files are local evidence and are not committed by default.

Required shared dependencies:

- Redpanda/Kafka with `telemetry.raw.v1` and compacted
  `source.runtime-config.v1`.
- Redis for idempotency keys, rate-limit counters, and connector leases.
- `INGESTION_MULTI_REPLICA_MODE=true`,
  `INGESTION_IDEMPOTENCY_REDIS_ENABLED=true`,
  `INGESTION_RATE_LIMIT_REDIS_ENABLED=true`, and
  `INGESTION_LOCAL_CORRECTNESS_STATE_ENABLED=false`.

Expected results:

- HTTP push telemetry is accepted through either replica.
- Duplicate telemetry sent to another replica is counted once.
- Rate-limit counters are shared across replicas.
- Exactly one replica owns each active connector source lease.
- A stale owner generation cannot continue polling or forwarding subscription
  telemetry after failover.
- A surviving replica acquires ownership after the owner process stops.
- `telemetry.raw.v1` records are keyed by `deviceId`.

Troubleshooting:

- If both replicas reject startup in multi-replica mode, verify Redis is
  reachable and the Redis-backed idempotency/rate-limit flags are enabled.
- If a restarted replica does not know a source, verify
  `source.runtime-config.v1` exists, is compacted, and consumers use
  replica-specific groups so each replica replays the source read model.
- If two replicas report `ownsConnectorLease=true` for the same
  `dataSourceId`, treat it as an architecture failure; do not increase the
  lease TTL to hide overlapping ownership.
- If failover is slow, compare the configured connector tick interval and lease
  TTL with the observed Redis lease key expiry.
