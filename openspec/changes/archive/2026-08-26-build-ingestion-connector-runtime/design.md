# Design: Build Ingestion Connector Runtime

## Runtime Components

Create a protocol-neutral runtime:

- `ConnectorRuntime` coordinates active sources.
- `SourceAssignmentManager` decides which sources this instance should try to
  run.
- `ConnectorLeaseManager` acquires, renews, and releases source ownership.
- `ConnectorScheduler` triggers polling work.
- `ConnectorSupervisor` manages lifecycle state and retry/backoff.
- `ConnectorHealthManager` reports per-source and aggregate health.

## Ownership

Every active connector source must have at most one runtime owner. Lease loss
stops the local connector before another instance starts work. The first
implementation can use Redis leases if already available; alternative stores are
allowed only if they support expiry, renewal, and failover tests.

## Adapter Boundary

Adapters expose polling or subscription operations. They do not decide global
source assignment, lease acquisition, or retry policy. The runtime invokes the
adapter after ownership is acquired.

## Testing

Test lease acquire/renew/release, lease loss, connector restart, config update,
source disabled while running, retry backoff with jitter, and isolated failure
of one connector source.
