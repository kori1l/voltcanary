# Design: Add OPC UA Ingestion Adapter

## Adapter Config

OPC UA config should include endpoint URL or reference, security policy, auth
or certificate references, session timeout, subscription settings, node mapping,
timestamp policy, metric mapping, and config schema version.

## Runtime Flow

```text
ConnectorRuntime
  -> lease-owned OPC UA source
  -> OpcUaAdapter.startSubscription(sourceConfig)
  -> node change mapped to metrics
  -> IncomingTelemetryMessage
  -> Common Ingestion Pipeline
```

The runtime owns source lease, lifecycle, reconnect/backoff, and stopping after
lease loss. The adapter owns OPC UA session mechanics and node-to-metric mapping.

## Failure Handling

Session loss, security/auth failure, invalid node mapping, and endpoint
unavailability update per-source connector health. Long-lived sockets and
sessions remain local to one process and are closed on lease loss.

## Testing

Use an OPC UA test server or adapter stub to cover subscription start, node
change mapping, session reconnect, lease loss, credential failure, config
update, and successful telemetry publication.
