# Анализ коммерческой готовности VoltCanary

Статус: продуктовый и инженерный анализ, не OpenSpec change  
Дата: 2026-08-22  
Цель: понять, каких возможностей не хватает, чтобы платформу можно было продавать

## Короткий вывод

VoltCanary уже не выглядит как пустой прототип. В репозитории есть серьезная
техническая база: gateway с аутентификацией, реестр устройств, ingestion,
хранение телеметрии, аналитика, alerts/rules/notifications, maintenance
windows/outcomes, simulator, AI-agent, ML lifecycle, local Kubernetes,
Prometheus/Grafana/Loki, OpenSpec и большое количество контрактов/тестов.

Главный пробел сейчас не в том, что "нет еще одного алгоритма". Главный пробел
в коммерческой упаковке. Система похожа на сильный инженерный MVP, который
можно показывать в demo и довести до controlled paid pilot, но ее рано
продавать как self-service SaaS: не хватает tenant/workspace слоя, onboarding,
billing/quotas, production operations, customer support flows, enterprise
security, отчетности для покупателя и доказательства бизнес-ценности.

Практический путь:

1. Сначала продавать как managed paid pilot, а не как открытый self-service
   SaaS.
2. Перед оплатой реальным клиентом закрыть P0-пробелы: tenant/workspace,
   onboarding, production deployment, SLO/runbooks, support/admin, usage
   limits.
3. После первых pilot-клиентов добавлять self-service billing, quota
   enforcement, API keys, public API v1 и enterprise controls.

## Что было проверено

Локальные источники:

- [README.md](../README.md)
- [openspec/project.md](../openspec/project.md)
- [docs/architecture/01-system-overview.md](architecture/01-system-overview.md)
- [docs/architecture/02-service-boundaries.md](architecture/02-service-boundaries.md)
- [docs/architecture/06-kubernetes-deployment.md](architecture/06-kubernetes-deployment.md)
- [docs/architecture/07-observability.md](architecture/07-observability.md)
- [docs/architecture/09-horizontal-scaling-matrix.md](architecture/09-horizontal-scaling-matrix.md)
- [docs/architecture/10-consistency-contracts.md](architecture/10-consistency-contracts.md)
- [docs/api-gateway-routes.md](api-gateway-routes.md)
- [docs/identity-auth-contract.md](identity-auth-contract.md)
- [docs/api/device-api.md](api/device-api.md)
- [docs/api/ingestion-api.md](api/ingestion-api.md)
- [docs/api/telemetry-api.md](api/telemetry-api.md)
- [docs/api/analytics-api.md](api/analytics-api.md)
- [docs/api/alerts-api.md](api/alerts-api.md)
- [docs/api/ml-api.md](api/ml-api.md)
- [docs/operations/ml-operations.md](operations/ml-operations.md)
- [docs/flows/system-verification-report.md](flows/system-verification-report.md)
- [docs/system-improvements.md](system-improvements.md)
- frontend routes в [frontend/src/app/App.tsx](../frontend/src/app/App.tsx) и
  [frontend/src/app/AppLayout.tsx](../frontend/src/app/AppLayout.tsx)
- backend controllers под [backend/services](../backend/services)

Внешние ориентиры рынка и контроля:

- Azure IoT Central описывает типичный IoT product surface: быстрое подключение
  устройств, telemetry monitoring, rules, lifecycle management, bulk jobs,
  dashboards, security, audit logs, data export, scalability и HADR:
  <https://learn.microsoft.com/en-us/azure/iot-central/core/overview-iot-central>
  и
  <https://learn.microsoft.com/en-us/azure/iot-central/core/concepts-architecture>
- AWS IoT SiteWise делает акцент на industrial protocol ingestion, edge
  collection, stream filtering, asset metrics и operational dashboards:
  <https://aws.amazon.com/iot-sitewise/features/>
- ThingsBoard описывает коммерческую IoT platform через device management,
  data collection, processing, visualization, REST automation, rule engine,
  tenant isolation, quotas, tenant admins и white-labeling:
  <https://thingsboard.io/docs/pe/why-thingsboard/> и
  <https://thingsboard.io/docs/pe/user-guide/multi-tenancy/>
- NISTIR 8259A задает базовый ориентир по IoT device cybersecurity
  capabilities:
  <https://www.nist.gov/publications/iot-device-cybersecurity-capability-core-baseline>
- AICPA Trust Services Criteria часто используются как язык доверия для SaaS:
  security, availability, processing integrity, confidentiality, privacy:
  <https://www.aicpa-cima.com/resources/download/2017-trust-services-criteria-with-revised-points-of-focus-2022>
- AWS Well-Architected IoT Lens отдельно выделяет OTA как reliability/security
  critical capability, потому что неудачное обновление может привести к выезду
  техника:
  <https://docs.aws.amazon.com/wellarchitected/latest/iot-lens/over-the-air-ota-updates.html>
- Stripe Billing/Customer Portal показывает стандартные SaaS primitives:
  subscriptions, usage tracking, invoices, pricing models, trials,
  customer self-service:
  <https://docs.stripe.com/billing> и
  <https://docs.stripe.com/customer-management>

## Что уже является сильной базой

| Область | Что уже есть | Где смотреть |
| --- | --- | --- |
| Архитектура | microservices, API Gateway, Kafka pipeline, database-per-service boundaries | [service boundaries](architecture/02-service-boundaries.md) |
| Auth | registration/login/refresh/logout, password reset, email verification, admin user controls, RS256 JWT | [identity auth contract](identity-auth-contract.md) |
| Devices | accounts, locations, lifecycle, sensors, data sources, token rotation/revocation, CSV import/export | [device API](api/device-api.md) |
| Ingestion | HTTP/MQTT path, source-token auth, Redis idempotency/rate limits, import/backfill endpoints | [ingestion API](api/ingestion-api.md) |
| Telemetry | Timescale readings/latest/quality/metrics/export, DLQ | [telemetry API](api/telemetry-api.md) |
| Analytics | baselines, features, anomalies, risk, recommendations, jobs, prediction traceability | [analytics API](api/analytics-api.md) |
| Alerts | alert lifecycle, pagination, summary, support search, rules CRUD/effective/test, notification attempts | [alerts API](api/alerts-api.md) |
| Maintenance | operations, windows, outcomes, ML label readiness | [device API](api/device-api.md) |
| ML | dataset preview, labels, feature snapshots, training runs, model registry, deployment policies, artifact health | [ML API](api/ml-api.md) |
| Simulator | configs/templates/presets/start/stop/status/runs | [simulator-service](../backend/services/simulator-service) |
| Frontend | Devices, Maintenance, Alerts/Rules, Settings, ML Settings, Admin/Account, Assistant drawer | [App.tsx](../frontend/src/app/App.tsx) |
| Reliability | outbox/inbox, replay, leases, scheduler locks, consistency targets | [consistency contracts](architecture/10-consistency-contracts.md) |
| Local ops | Compose, local Kubernetes, Prometheus, Grafana, Loki, HPA/PDB for stateless workloads | [Kubernetes deployment](architecture/06-kubernetes-deployment.md) |

## Уровни готовности к продаже

### L0: Demo-ready

Цель: показать убедительный end-to-end сценарий.

VoltCanary близок к этому уровню: simulator генерирует telemetry, есть device
details, charts, alerts, rules, ML Settings и verification report.

Не хватает для чистого demo:

- one-click demo workspace seed;
- guided first-run path;
- curated сценарии: normal operation, power spike, offline window, consumption
  growth, failure-prediction lifecycle;
- browser-level smoke, который каждый раз доказывает demo path.

### L1: Paid pilot-ready

Цель: продавать ограниченному числу клиентов с ручным onboarding и managed
support.

Это ближайшая реалистичная коммерческая цель. Нужно закрыть:

- workspace/customer administration;
- tenant isolation review и видимые tenant admin controls;
- production deployment packaging;
- backup/restore и incident runbooks;
- onboarding/support workflows;
- несколько надежных real-world integrations;
- usage limits и manual billing/contract state;
- SLO targets и production monitoring.

### L2: Self-service SaaS-ready

Цель: клиент сам регистрируется, платит, подключает устройства, получает
ценность и апгрейдится без ручной работы команды.

Нужно:

- signup/trial/billing/subscription portal;
- plan enforcement и usage metering;
- quota-aware ingestion/retention/ML training limits;
- self-service onboarding;
- tenant admin/member invitations;
- product analytics и lifecycle emails;
- public docs, API keys, SDK examples, support intake.

### L3: Enterprise-ready

Цель: проходить procurement/security review крупных клиентов.

Нужно:

- SSO/SAML/OIDC, SCIM, MFA, custom RBAC;
- audit exports и admin activity logs;
- data residency, retention, DPA/GDPR workflows;
- SOC 2 readiness controls или эквивалентная evidence база;
- private deployment option;
- HADR, tested backup restore, load limits;
- SIEM/webhook integrations;
- security review package и pen-test process.

## Главные пробелы, которые мешают продаже

### 1. Нет полноценной модели Workspace/Tenant

Текущее состояние:

- В коде есть account-scoped data и `account_id`.
- В identity есть `USER`, `SUPPORT`, `ADMIN`.
- В device/alert есть ownership checks.
- Но это еще не customer-facing workspace: нет organizations, members,
  tenant admins, invitations, quotas, plan state, billing state, tenant-level
  settings.

Почему нужно:

- Покупатель покупает workspace для компании/объекта/команды, а не личный
  аккаунт одного пользователя.
- Procurement спросит: кто владелец tenant, кто приглашает людей, кто видит
  данные, что происходит при увольнении сотрудника.
- Конкуренты в IoT обычно продают tenant isolation и tenant administration как
  часть платформы.

Нужные фичи:

- `Workspace` или `Organization`: owner, admins, operators, analysts, viewers,
  billing admin, support access policy, status, plan, quotas.
- User invitations и membership lifecycle.
- Tenant settings: timezone, notification defaults, retention, ML defaults,
  branding metadata, support contacts.
- Tenant usage counters: devices, data sources, telemetry points, storage,
  notification attempts, ML training minutes, model artifacts.
- Tenant suspension behavior: disable ingestion, preserve read-only historical
  access, pause notifications, block ML training.
- Tenant audit viewer for admin/sensitive operations.

Acceptance criteria:

- Customer admin может приглашать и удалять пользователей без platform admin.
- Support user получает доступ к tenant только через явный support grant.
- Quotas enforced на ingestion, device creation, exports, ML training,
  notification delivery.
- Tenant deletion/suspension имеет документированный lifecycle и тесты.

### 2. Нет Billing, Plans, Usage Metering, Trials

Текущее состояние:

- Billing/subscription/invoice/trial model в репозитории не найден.
- Есть operational counters и rate limits, но нет billable usage.

Почему нужно:

- Нельзя продавать self-service SaaS, если система не знает, кто имеет право
  пользоваться продуктом, что куплено, сколько потреблено и когда нужно
  предупредить/ограничить.
- IoT pricing обычно привязан к количеству устройств, объему данных, retention,
  alerts и advanced analytics/ML usage.
- Для первого paid pilot full Stripe можно отложить, но entitlement/usage
  model нужен рано, иначе монетизацию будет трудно встроить потом.

Нужные фичи:

- Plans: `Pilot`, `Team`, `Business`, `Enterprise`.
- Entitlements: max devices, max telemetry points/month, retention days,
  ML training enabled, advanced alerting enabled, data export enabled, support
  tier.
- Usage meters: active devices, ingested readings, retained GB, exported rows,
  notification deliveries, ML training runs/minutes, model artifact storage.
- Trial lifecycle: start, expire, convert, grace period, suspend.
- Billing provider integration или manual invoice mode for pilots.
- Customer billing portal для self-service релиза.

Acceptance criteria:

- У каждого tenant есть plan и entitlement set.
- Ingestion/ML workflows умеют reject/degrade при quota exceeded.
- Admin видит current-month usage и projected overage risk.
- Pilot customer можно вручную отметить как paid без полной payment
  automation.
- Self-service release поддерживает subscriptions, invoices, customer portal.

### 3. Production deployment пока development-oriented

Текущее состояние:

- Local Kubernetes использует source-code `hostPath` mounts и runtime images.
- Документация прямо говорит, что для production нужны immutable images,
  registry, external secrets, ingress/TLS, stateful HA, backups, network
  policies, service monitors, dashboards, alert rules, runbooks.

Почему нужно:

- Клиенты платят за service availability, а не за local dev topology.
- Без immutable images нельзя нормально трассировать release, rollback и
  incident.
- Без backup/restore и HADR можно потерять customer telemetry и alert history.

Нужные фичи:

- CI pipeline: backend JARs, frontend assets, ML image, service images,
  immutable tags by git SHA/release.
- Image registry и environment-specific Helm values.
- Ingress/Gateway API with TLS and trusted forwarded headers.
- Secret manager: SOPS, External Secrets Operator, Vault или cloud secrets.
- NetworkPolicies и internal service access restrictions.
- Stateful production direction: managed PostgreSQL/Timescale, managed Redis,
  Redpanda cluster/managed Kafka, S3/managed object storage.
- Database migration jobs и rollback policy.
- Backup/restore procedures with restore tests.
- Release checklist и rollback runbook.

Acceptance criteria:

- Чистый cluster разворачивает VoltCanary только из images.
- Production secret values не живут в git/plain manifests.
- Restore from backup проверен на disposable environment.
- Failed release можно откатить к known image/schema state.

### 4. SLO, Runbooks и Production Monitoring неполные

Текущее состояние:

- Prometheus/Grafana/Loki есть.
- Operational metrics в сервисах есть.
- Документация фиксирует gaps: distributed tracing, app-specific dashboards,
  Prometheus alert rules, runbooks, production log retention.

Почему нужно:

- Pilot customer спросит: "кто узнает, если alerts перестанут создаваться?".
- В IoT silent ingestion failure хуже, чем явная ошибка UI.
- Покупатель должен верить, что missing data, delayed alerts и model fallback
  замечаются до того, как он сам это обнаружит.

Нужные фичи:

- SLO:
  - ingestion acceptance availability;
  - ingestion-to-telemetry latency;
  - telemetry-to-alert latency;
  - dashboard API latency;
  - notification delivery latency;
  - ML fallback rate;
  - data freshness per tenant/device.
- Dashboards per service и per customer tenant.
- Alerts for Kafka lag, outbox age, DLQ growth, failed notification attempts,
  failed ML artifact health, Redis failures, storage pressure, stale devices,
  no-data devices.
- Runbooks for every production alert.
- Trace propagation across gateway, Java services, Kafka workers, ML calls,
  notification delivery.
- Log retention/search policy.

Acceptance criteria:

- Synthetic telemetry probe подтверждает path: ingestion -> telemetry ->
  dashboard -> alert.
- Каждый critical production alert ссылается на runbook.
- Operator видит "which tenants are degraded?" на одном экране.
- Correlation IDs/traces позволяют пройти одну reading через систему.

### 5. Onboarding еще не является продуктом

Текущее состояние:

- Device creation, data sources, tokens, simulator и charts есть.
- Но first value пока требует понимания экранов, API docs, tokens и иногда
  simulator YAML.

Почему нужно:

- Buyer оценивает time-to-first-value.
- Если первый chart или первый alert требует ручного чтения контрактов,
  conversion будет слабым.

Нужные фичи:

- First-run checklist:
  - create workspace;
  - create location;
  - add real/demo device;
  - create source token;
  - send first reading;
  - view chart;
  - create alert rule;
  - receive test notification.
- Guided device setup wizard:
  - real HTTP collector;
  - MQTT collector;
  - simulator/demo;
  - CSV/backfill.
- Copy-paste collector examples: curl, Python, Node, MQTT.
- Source-token handling: one-time display, rotate, revoke, test.
- Empty states leading to a concrete next action.
- Demo workspace seed with realistic data and alert scenarios.

Acceptance criteria:

- Новый пользователь доходит до первого chart без чтения repo docs.
- Новый пользователь создает known alert через demo device меньше чем за
  10 минут.
- Setup steps сохраняют progress и могут быть resumed.

### 6. Integration surface слишком узкий для продаж

Текущее состояние:

- HTTP, MQTT, CSV/import и simulator есть.
- Нет packaged edge collector, Home Assistant connector, Modbus/OPC-UA bridge,
  Slack/PagerDuty/Opsgenie native adapters, customer data export connectors.

Почему нужно:

- Продукт продается быстрее, когда подключается к тому, что уже есть у
  клиента.
- Industrial/building customers часто используют gateways, MQTT brokers,
  Modbus, OPC-UA, vendor APIs, CSV historians и existing incident systems.

Нужные фичи:

- Collector SDK и sample agents:
  - HTTP collector;
  - MQTT collector;
  - Docker edge collector;
  - optional Windows service collector later.
- Gateway/edge mode:
  - local buffering;
  - retry;
  - clock skew detection;
  - compression/batching;
  - offline status reporting.
- Integrations:
  - Home Assistant;
  - generic MQTT broker bridge;
  - Modbus TCP или OPC-UA bridge for industrial pilots;
  - webhooks out;
  - Slack/PagerDuty/Opsgenie adapters;
  - S3/Azure Blob/Event Hub export.
- Integration health screen per tenant.

Acceptance criteria:

- Pilot customer может подключить хотя бы один real source без custom coding.
- Failed exports/integrations visible and retryable.
- Integrations документируют auth, limits, retry, failure semantics.

### 7. Device Fleet Operations неполные

Текущее состояние:

- Есть devices, locations, sensors, data sources, maintenance, telemetry.
- Не подтверждены command/control model, bulk device jobs, firmware/OTA update,
  device twin/properties model, fleet policy engine.

Почему нужно:

- Monitoring полезен, но IoT-покупатель ожидает fleet operations: configure
  devices, rotate tokens, schedule jobs, update firmware, detect stale devices,
  bulk apply policies.
- OTA/remote update safety критичны: failed update может вызвать field service.

Нужные фичи:

- Fleet overview:
  - online/offline/stale;
  - active alerts;
  - highest risk devices;
  - top locations;
  - telemetry freshness;
  - data quality.
- Bulk jobs:
  - rotate source tokens;
  - apply analytics config;
  - apply alert rules;
  - export devices;
  - schedule maintenance;
  - tag/move devices.
- Device properties/twin:
  - reported properties;
  - desired configuration;
  - last sync status.
- Command model:
  - command definition;
  - target devices;
  - queued/sent/acknowledged/failed;
  - audit.
- OTA/config update roadmap:
  - package metadata;
  - staged rollout;
  - canary;
  - rollback;
  - checksum/signature;
  - failure recovery.

Acceptance criteria:

- Operator находит stale/noisy/high-risk devices без открытия каждого device.
- Tenant admin выполняет safe bulk action с preview и audit.
- Remote commands/updates всегда имеют per-target status.

### 8. Alerting требует incident-management polish

Текущее состояние:

- Alert lifecycle, rules, notification channels, preferences, attempts,
  escalation, suppression и support search есть.
- Rule test без device context возвращает valid-but-not-evaluated; полноценный
  telemetry-backed preview зависит от device.
- Provider-neutral delivery есть, но real provider adapters/secret resolution
  не выглядят полностью продуктово упакованными.

Почему нужно:

- Alerts - ежедневная ценность продукта.
- Покупатель должен доверять, что rules можно test/tune/route/suppress/escalate
  и audit.

Нужные фичи:

- Incident inbox with grouping by device/rule/time window.
- Alert investigation view:
  - source rule/model;
  - telemetry window;
  - related maintenance;
  - risk drivers;
  - delivery attempts;
  - recommended action;
  - false-positive/expected behavior feedback.
- Rule test against recent telemetry for fleet/location/device-type/device.
- Rule version history and rollback.
- Notification routing:
  - severity;
  - location;
  - device type;
  - quiet hours;
  - escalation policy;
  - fallback channel.
- Native adapters или documented webhook templates.
- False-positive/outcome feedback loop into ML labels.

Acceptance criteria:

- User понимает, почему alert fired, без чтения IDs/JSON.
- User тестирует rule перед enable.
- Notification failures visible/retryable и не путаются с "alert did not
  happen".

### 9. Нет buyer-facing reporting и ROI evidence

Текущее состояние:

- Есть telemetry, analytics, alerts, ML traceability, maintenance outcomes.
- Нет явного report package: weekly report, downtime avoided, energy anomaly
  summary, false positives, maintenance outcomes, exportable PDF/CSV report.

Почему нужно:

- Operator работает с daily queue, но buyer продлевает контракт из-за доказанной
  value.
- Predictive maintenance продается через reduced downtime, earlier detection,
  fewer truck rolls, lower energy waste, better maintenance planning.

Нужные фичи:

- Fleet summary report:
  - active devices;
  - telemetry freshness;
  - alerts opened/resolved;
  - MTTA/MTTR;
  - high-risk devices;
  - maintenance windows;
  - suppressed alerts during maintenance.
- Value report:
  - confirmed failures predicted;
  - lead time before failure;
  - false alerts;
  - recurring anomalies;
  - energy consumption growth;
  - estimated avoided downtime, если customer supplies cost assumptions.
- Export:
  - CSV raw readings and alert history;
  - PDF/shareable report for management;
  - scheduled email report.
- Model quality report:
  - fallback rate;
  - shadow/canary comparison;
  - precision/recall when outcomes exist;
  - drift.

Acceptance criteria:

- Pilot review использует VoltCanary-generated report для buy/no-buy decision.
- Reports clearly distinguish observed facts from estimates.
- Export respects tenant authorization and retention policy.

### 10. Security/compliance еще не procurement-ready

Текущее состояние:

- Есть хорошая база: JWT, gateway header stripping, account ownership,
  internal service token, source tokens, encrypted AI provider secrets, audit
  logs в нескольких сервисах.
- Для enterprise sale не хватает: MFA, SSO/SAML/OIDC, SCIM, custom RBAC,
  support access workflow, centralized audit export, secret manager,
  mTLS/workload identity, data retention/privacy workflows, security evidence
  package, pen-test results.

Почему нужно:

- Даже малый B2B-клиент спросит, как изолированы данные и кто имеет доступ.
- Крупные клиенты спрашивают SOC 2-style evidence: security, availability,
  confidentiality, privacy, processing integrity.
- IoT systems имеют device-side risk: weak credentials, insecure update,
  device identity, data protection.

Нужные фичи:

- MFA for local accounts.
- SSO/OIDC/SAML for business plans.
- SCIM for enterprise user lifecycle.
- Workspace roles:
  - owner;
  - admin;
  - operator;
  - analyst;
  - billing admin;
  - read-only viewer;
  - support access.
- Support access grants with expiry, reason, audit.
- Audit event stream/export: auth, device, source token, rule, alert, ML,
  billing, support, admin.
- Production secret management.
- Workload identity или mTLS для internal services.
- Data retention controls and deletion/export request workflow.
- Security checklist mapped to NIST IoT baseline and SOC 2 categories.

Acceptance criteria:

- Tenant admin видит, кто доступался или менял sensitive resources.
- Production не опирается на shared dev tokens.
- Procurement packet содержит architecture, data flow, access controls,
  backup/restore, incident response, vulnerability management.

### 11. Public API, SDK и webhooks не продуктово оформлены

Текущее состояние:

- Service APIs/docs есть.
- Некоторые сервисы имеют OpenAPI через springdoc.
- Public routes пока unversioned; docs говорят, что новые external contracts
  стоит вводить под `/api/v1/...`.

Почему нужно:

- IoT platform становится полезной, когда customer может автоматизировать
  device management, export data, downstream systems.
- Unversioned API делает customer integrations risky.

Нужные фичи:

- Public API versioning under `/api/v1`.
- Tenant-scoped API keys/service accounts, separate from user JWTs.
- API key scopes:
  - ingest;
  - read telemetry;
  - manage devices;
  - manage alerts;
  - export;
  - admin.
- Webhook subscriptions:
  - alert created/escalated/resolved;
  - device offline/stale;
  - model deployed;
  - quota exceeded;
  - integration failed.
- SDK/examples:
  - curl;
  - Python;
  - Node;
  - MQTT.
- Developer docs: auth, retries, limits, idempotency, error codes.

Acceptance criteria:

- Customer integration не использует human user's refresh token.
- API deprecations имеют versioning и migration policy.
- Webhook delivery signed, retryable, observable.

### 12. ML сильный технически, но требует trust packaging

Текущее состояние:

- ML lifecycle уже серьезный: datasets, preview, labels, feature snapshots,
  training runs, model registry, policies, artifact health, approval gates,
  fallback statuses, traceability.
- Buyer-facing trust layer слабее: model card UX, shadow/canary comparison,
  false-positive review, drift dashboard, business-language limitations должны
  быть видны сразу.

Почему нужно:

- Predictive claims опасны, если клиент не понимает confidence, training data,
  horizon, limitations, false-positive rate.
- VoltCanary нужно продавать как "explainable risk and operational signals",
  а не как точную диагностику поломки по слабым данным.

Нужные фичи:

- Model cards in UI:
  - intended use;
  - trained device types;
  - supported metrics;
  - training window;
  - label counts;
  - metrics;
  - limitations.
- Shadow/canary comparison dashboard.
- Drift/fallback/feature-mismatch dashboard.
- Prediction feedback:
  - expected behavior;
  - false positive;
  - confirmed failure;
  - sensor fault;
  - unresolved.
- Plain-language ML state in device and alert views.
- Guardrails против enabling failure prediction without enough outcomes.

Acceptance criteria:

- Customer видит, prediction came from model/heuristic/fallback/rule.
- ML UI не провоцирует unsupported production claims.
- Model deployment имеет audit trail и rollback path.

## Приоритетный roadmap

### P0: минимум для paid pilot

1. Workspace/Tenant model
   - Org/workspace, memberships, invitations, tenant settings, tenant status,
     support grants, usage counters.
   - Почему: customer покупает account for team/company, not single user.

2. Managed onboarding and first-run checklist
   - Setup checklist, guided device/source flow, test telemetry sender, demo
     seed, first alert scenario.
   - Почему: sales conversion зависит от time-to-first-value.

3. Production deployment supply chain
   - Immutable images, registry, env overlays, ingress/TLS, external secrets,
     migration jobs, backup/restore.
   - Почему: real customer требует repeatable deployment и rollback.

4. SLO dashboard and runbooks
   - Service dashboards, production alerts, synthetic ingestion probe,
     correlation trace path, runbooks.
   - Почему: customer-impacting failures must be detected early.

5. Alert/incident workflow polish
   - Incident inbox, rule test UX, rule history, notification routing,
     investigation view.
   - Почему: alert handling - основная ежедневная ценность.

6. Pilot commercial controls
   - Manual plan/entitlement state and usage view; full payment automation can
     wait.
   - Почему: paid tenant должен иметь limits and contract state.

7. Support/admin console
   - Tenant list, tenant health, user/session/device/ingestion health, support
     access grants, audit.
   - Почему: managed pilot нельзя поддерживать через direct DB access.

8. Browser and E2E release gate
   - Smoke: signup/login, create demo device, generate telemetry, chart, rule,
     alert, notification attempt, ML fallback/trained path.
   - Почему: demos and pilots must be reproducible.

### P1: self-service SaaS

1. Stripe/billing-provider integration
   - Subscriptions, trials, invoices, customer portal, webhooks, plan changes.

2. Quota enforcement
   - Devices, readings/month, storage, retention, notification attempts, export
     rows, ML training usage.

3. Public API v1 and API keys
   - Versioned APIs, scoped keys, SDK examples, webhook signing.

4. Product analytics
   - Activation funnel, setup completion, first telemetry, first alert, first
     notification, weekly active tenant, churn signals.

5. Customer-facing reports
   - Weekly fleet health, alert outcomes, value summary, exportable PDF/CSV.

6. Integration marketplace basics
   - Slack, email provider, PagerDuty/Opsgenie, S3/Blob export, Home Assistant
     or generic MQTT bridge.

### P2: enterprise and OEM

1. SSO/OIDC/SAML, MFA, SCIM.
2. Custom RBAC with role templates and per-resource permissions.
3. Tenant white-labeling: logo, colors, custom domain, report/email branding.
4. Data residency and private deployment.
5. Industrial connectors: OPC-UA, Modbus TCP, historian import, SCADA/BMS
   bridge.
6. Security/compliance package: SOC 2 readiness controls, vulnerability
   management, pen-test process, incident response, audit exports,
   DPA/privacy workflows.
7. HADR and load certification: multi-zone dependencies, DR tests, documented
   load envelopes per plan.

## Рекомендуемый первый продаваемый пакет

Не начинать с self-service SaaS. Начать с managed pilot.

Название: VoltCanary Managed Pilot

Кому продавать:

- small facility operator;
- smart appliance service company;
- property/facility manager;
- appliance maintenance provider;
- small OEM validating connected-device analytics.

Pilot limits:

- 1 workspace;
- 1-3 customer users;
- 5-50 devices;
- HTTP/MQTT/simulator ingestion;
- 30-90 days telemetry retention;
- alert rules and webhook/Slack/PagerDuty-like delivery;
- weekly report;
- managed onboarding;
- manual billing/contract;
- business-hours support.

Pilot success metrics:

- time from kickoff to first real telemetry;
- percentage of devices sending fresh data;
- alerts created and resolved;
- false-positive rate;
- confirmed useful anomalies;
- maintenance outcomes recorded;
- buyer-visible report generated;
- customer says the platform saved time, reduced uncertainty, or exposed a
  previously invisible issue.

Не продавать пока такие обещания:

- guaranteed failure prediction accuracy;
- autonomous maintenance decisions;
- enterprise-grade HA/DR;
- SOC 2 compliant;
- universal device compatibility;
- zero-touch self-service onboarding.

## Suggested OpenSpec change cuts

Не делать это одним огромным change. Каждая реализация ниже меняет product
behavior/API/data/frontend workflow и должна идти через отдельный OpenSpec
change.

1. `productize-workspaces-and-tenants`
   - workspace/org domain, membership, invitations, tenant settings, support
     grants, tenant audit.

2. `add-usage-metering-and-entitlements`
   - plan model, quotas, usage counters, quota enforcement, tenant suspension.

3. `harden-production-deployment`
   - immutable images, registry, Helm/env overlays, ingress/TLS, secrets,
     migration jobs, network policies, backup/restore.

4. `build-commercial-onboarding-flow`
   - first-run checklist, guided device/source setup, test telemetry sender,
     demo seed, activation tracking.

5. `add-customer-operations-console`
   - tenant health, support search, support access grants, production incident
     diagnostics.

6. `formalize-slo-observability-runbooks`
   - SLOs, dashboards, Prometheus alerts, runbooks, traces, synthetic probes.

7. `add-public-api-v1-and-api-keys`
   - external API versioning, API keys, scopes, SDK examples, signed webhooks.

8. `add-billing-and-trial-lifecycle`
   - subscriptions, billing portal, invoices, trial lifecycle, billing webhooks.

9. `productize-alert-incident-management`
   - incident inbox polish, rule test at scope, rule versions, routing,
     false-positive feedback.

10. `add-enterprise-security-controls`
    - SSO/MFA/SCIM/custom RBAC, audit exports, data retention/deletion,
      compliance evidence.

## Что отложить

Эти вещи могут звучать красиво, но их лучше не делать до коммерческой базы.

- Deep learning/LSTM forecasting как headline feature.
- Mobile app до надежного web onboarding and notifications.
- Полный configurable dashboard builder до fleet overview и reports.
- Marketplace из десятков integrations до 3-5 реально нужных и надежных.
- Multi-region active-active до tested single-region backup/restore и load
  limits.
- Full SOC 2 audit до operating controls, evidence collection, incident
  response.

## Bottom line

VoltCanary имеет достаточно технической глубины, чтобы стать продаваемым
продуктом. Но сейчас нужно меньше добавлять внутренних capabilities и больше
упаковывать существующие capabilities в покупательский путь:

- workspace/tenant administration;
- reliable onboarding;
- production deployment and support operations;
- quotas and commercial state;
- incident workflow polish;
- reporting that proves value;
- security and compliance evidence.

После P0 платформу можно честно продавать как managed paid pilot. После P1 -
двигаться к self-service SaaS. После P2 - идти в enterprise/OEM deals.
