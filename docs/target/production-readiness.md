# Production Readiness Target

The current Docker Compose and Kubernetes manifests are local development and
validation environments. They prove service discovery, dependency wiring,
basic resilience, and smoke flows, but they are not a production release
process.

## Deployment Gaps

Before using VoltCanary for a real environment, close these gaps:

- build immutable images for every service and publish them to a registry;
- replace source-code `hostPath` mounts with image contents;
- use external secret management and rotation;
- add Ingress or Gateway API with TLS;
- configure trusted proxy handling for `X-Forwarded-*` headers;
- replace shared internal service tokens with workload identity, mTLS, or
  another controlled service identity model;
- configure NetworkPolicies and least-privilege service access;
- run PostgreSQL, TimescaleDB, Redis, Redpanda, and object storage as managed
  services or correctly clustered stateful systems;
- define persistent volume classes, backup, restore, and rollback procedures;
- add ServiceMonitor resources or equivalent scrape configuration for
  production observability;
- define SLOs, alert rules, dashboards, runbooks, and incident ownership.

## Stateful HA Direction

Stateful services must not be scaled by only changing `replicas`.

| Component | Production HA direction |
| --- | --- |
| PostgreSQL | Managed PostgreSQL, Patroni, CloudNativePG, or another Postgres operator |
| TimescaleDB | Managed Timescale/PostgreSQL or a Postgres HA operator with Timescale support |
| Redis | Redis Sentinel, Redis Cluster, or managed Redis |
| Redpanda | Multi-broker Redpanda cluster with replicated topics and correct advertised listeners |
| MinIO/Object storage | Distributed MinIO or managed object storage |

## Product And Enterprise Gaps

The codebase does not currently implement open self-service SaaS or enterprise
administration. These remain target product areas:

- workspace and tenant administration;
- onboarding, metering, billing, and entitlements;
- public API keys and customer-facing API versioning;
- support operations and customer-facing reports;
- MFA, SSO, SCIM, custom RBAC, audit export, and retention controls.

## Connector Hardening Gaps

HTTP polling, Modbus TCP, OPC UA, MQTT, and simulator ingestion paths exist in
the codebase, but production connector operation still needs environment-level
work before use against customer assets:

- managed credential and certificate storage with rotation workflows;
- per-connector network egress policy and allowlists;
- protocol-level integration tests against representative broker, HTTP,
  Modbus, and OPC UA endpoints;
- connector health dashboards, alert rules, and operator runbooks;
- customer-facing configuration validation and troubleshooting guidance.

## Event Integration Gaps

The current code publishes some integration topics before production consumers
or operating workflows exist for them. These are target work, not current
runtime dependencies:

- define and implement downstream consumers or retention/audit handling for
  `device-registry.changed.v1`;
- decide whether alert lifecycle should remain database-local or publish Kafka
  topics such as `alert.created.v1`, `alert.acknowledged.v1`, and
  `alert.resolved.v1`;
- add DLQ replay tooling and operator runbooks for `telemetry.raw.dlq.v1`,
  `analytics.telemetry.validated.dlq.v1`, and alert-service listener DLQs;
- align Redpanda topic provisioning with the DLQ topic names emitted by service
  code and remove unused legacy `*.v1.dlq` topics when no longer needed;
- standardize whether new topics use `EventEnvelope<T>` values or
  direct-record values with top-level envelope metadata.
