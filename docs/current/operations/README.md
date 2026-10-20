# Operations Docs

Operations docs explain how to run, configure, troubleshoot, or validate
runtime behavior. They should contain commands and failure-mode expectations,
not product plans.

## Guides

| Topic | Document |
| --- | --- |
| Local Kubernetes setup | [k8s-local.md](k8s-local.md) |
| Ingestion active connectors and horizontal ingestion smoke | [ingestion-connectors.md](ingestion-connectors.md) |
| Real Helsinki trams, HSL MQTT connector and common fleet map | [hsl-live-vehicles.md](hsl-live-vehicles.md) |
| Load-test profile preflight and evidence files | [load-testing.md](load-testing.md) |
| ML operations and artifact/runtime checks | [ml-operations.md](ml-operations.md) |
| Repository and Java package structure | [repository-structure.md](repository-structure.md) |

## Related Docs

- Deployment architecture: [../architecture/06-kubernetes-deployment.md](../architecture/06-kubernetes-deployment.md)
- Horizontal scaling matrix: [../architecture/09-horizontal-scaling-matrix.md](../architecture/09-horizontal-scaling-matrix.md)
- Verification flows: [../flows/README.md](../flows/README.md)

[Device variables](device-variables.md): rollout flags, isolated broker/database acceptance and rollback.
