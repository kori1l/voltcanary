# Design: Add HTTP Polling Ingestion Adapter

## Adapter Config

HTTP polling source config should include method, URL or endpoint reference,
headers/credential references, interval, timeout, response mapping, timestamp
mapping, metric mapping, and config schema version. Raw credentials must be
resolved through the credential provider.

## Runtime Flow

```text
ConnectorScheduler
  -> lease-owned source
  -> HttpPollingAdapter.poll(sourceConfig)
  -> IncomingTelemetryMessage
  -> Common Ingestion Pipeline
```

The runtime owns scheduling and retry policy. The adapter owns HTTP request
execution, response parsing, and payload-to-metric mapping.

## Raw Payload Policy

Canonical telemetry should include mapped metrics and bounded metadata only.
Raw HTTP response bodies can be logged only as redacted fingerprints or emitted
through a separately designed diagnostic channel.

## Testing

Use mock HTTP servers or local stubs to test successful poll, timeout, retry,
credential resolution, invalid mapping, disabled source, and canonical telemetry
publication.
