# API Docs

API docs describe HTTP contracts. Gateway and identity docs define access
boundaries; service docs define request/response behavior and compatibility.

## Entry Points

| Topic | Document |
| --- | --- |
| Gateway public/protected routes, JWT forwarding, rate limits | [api-gateway-routes.md](api-gateway-routes.md) |
| Identity auth, admin users, token contract | [identity-auth-contract.md](identity-auth-contract.md) |

## Service Contracts

| Service | Document |
| --- | --- |
| `identity-service` public auth surface | [auth-api.md](auth-api.md) and [identity-auth-contract.md](identity-auth-contract.md) |
| `device-service` accounts, locations, devices, sources, maintenance | [device-api.md](device-api.md) |
| `ingestion-service` HTTP ingestion, imports, MQTT, connector source config | [ingestion-api.md](ingestion-api.md) |
| `telemetry-service` readings, latest, quality, metrics, exports | [telemetry-api.md](telemetry-api.md) |
| `analytics-service` baselines, features, anomalies, risk, recommendations | [analytics-api.md](analytics-api.md) |
| `alert-service` alerts, rules, notification channels and attempts | [alerts-api.md](alerts-api.md) |
| `ml-service` datasets, feature snapshots, labels, training, models, inference | [ml-api.md](ml-api.md) |

## Related Docs

- Event contracts: [../events/README.md](../events/README.md)
- Service boundaries: [../architecture/02-service-boundaries.md](../architecture/02-service-boundaries.md)
- Security: [../architecture/08-security.md](../architecture/08-security.md)
