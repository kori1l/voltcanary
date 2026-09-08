# Target Architecture

This folder is the entry point for where VoltCanary is going. Content here may
include behavior that is partially implemented, provisioned but unused, or not
implemented yet. Target-only behavior must not be described as current runtime
behavior.

## Start Here

| Topic | Document |
| --- | --- |
| Platform target architecture, current baseline, and target gaps | [architecture.md](architecture.md) |
| Production readiness gaps | [production-readiness.md](production-readiness.md) |
| Target alert lifecycle Kafka topics | [alert-lifecycle-events.md](alert-lifecycle-events.md) |
| Notification delivery operations target | [notification-delivery-operations.md](notification-delivery-operations.md) |
| ML dataset workbench target design | [ml-dataset-workbench.md](ml-dataset-workbench.md) |
| Product roadmap context | [product-roadmap.md](product-roadmap.md) |
| Interview/demo talking points | [talking-points.md](talking-points.md) |

## Target-Only Items Currently Called Out

| Item | Status |
| --- | --- |
| Production deployment with immutable images, external secrets, ingress/TLS, stateful HA, network policies, SLOs, backup/restore, and runbooks | [production-readiness.md](production-readiness.md) |
| Workspace/tenant administration, onboarding, metering, billing, support operations, public API keys, and enterprise controls | [product-roadmap.md](product-roadmap.md) and [production-readiness.md](production-readiness.md) |
| Kafka alert lifecycle topics such as `alert.created.v1`, `alert.acknowledged.v1`, and `alert.resolved.v1` | [alert-lifecycle-events.md](alert-lifecycle-events.md) |
| Notification test delivery, channel diagnostics, and bounded retry operations | [notification-delivery-operations.md](notification-delivery-operations.md) |
| Production hardening for real connector credentials, certificates, network egress, protocol integration tests, dashboards, and runbooks | [production-readiness.md](production-readiness.md) |
| Production consumers/runbooks for `device-registry.changed.v1` and DLQ replay tooling | [production-readiness.md](production-readiness.md) |

For current behavior, start at [../current/README.md](../current/README.md).
