# Tasks: Серверные личные дашборды, версии и предпочтения

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-private-dashboard-domain`, затем соответствующие
project surface skills. Покрытие: DB-08, DB-09, DB-10, DB-12, DB-22, DB-33, DB-38, DB-39, DB-40, DB-43.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Зафиксировать ADR Dashboard domain/service8089/database и port/read ownership boundaries.
- [x] 1.2 Определить DTO/schema validation, secret/code rejection, widget payload bounds и consistent error statuses.
- [x] 1.3 Добавить module, database initialization/Flyway, health/readiness и idempotency/revision/preferences storage.
- [x] 1.4 Реализовать private CRUD/cursor list и server-side active account authorization.
- [x] 1.5 Реализовать author draft ETag, atomic publish/revisions/rollback-as-new-draft и409 conflict.
- [x] 1.6 Реализовать body-bound idempotency24h с permission recheck при replay.
- [x] 1.7 Реализовать copy без ACL, archive/restore и preference fallback после archive/noaccess.
- [x] 1.8 Добавить gateway routes, Compose/Kubernetes/Helm wiring согласованно с принятой ADR.
- [x] 1.9 Покрыть cross-user IDs/history/drafts, supplied ownership spoof, quota, double create/publish и conflict.
- [x] 1.10 Покрыть unavailable account, disable user, archive default, restore ID и unknown widget schema preservation.
- [x] 1.11 Выполнить focused dashboard/gateway Maven tests, Flyway fresh/upgrade и local health/auth smoke.
- [x] 1.12 Обновить current dashboard API/domain docs только после implementation; проверить structure/OpenSpec и инфраструктуру.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Выполнить focused Maven/migration/authorization integration проверки затронутых сервисов; не принимать readiness по одному health response.
- [x] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.

## Acceptance evidence — 2026-10-08

ADR-011 records service8089/private PostgreSQL/domain ports. Focused application
use cases own catalog/drafts/publication/lifecycle/preferences; HTTP ownership and
JDBC are adapters. Schema bounds and recursive unsafe-content checks preserve
safe unsupported widget payloads inertly. V1 persists author drafts, immutable
revisions, preferences, lifecycle audit and actor/route/body-bound24h outcomes.

Final dashboard reactor: 14 tests pass. PostgreSQL integration exercises fresh
Flyway and non-destructive migration rerun; owner/account spoof and foreign
read/draft/history/copy; quota; stable cursor pages; concurrent create and publish
retry; expired/body-mismatched keys; stale ETag/base; immutable history; rollback;
archive/default/restore; unsupported payload and denied bindings in copy. Signed
JWT HTTP ownership tests cover disabled/mismatched accounts, dependency503/401
and no cached authorization. Gateway reactor passes24 tests plus identity30;
publication/draft CORS headers are verified. AGENTS remains applicable; backend
and local-ops skills now include the service/port.

Compose fresh/existing database initializer ran and service/gateway health are UP;
readiness includes DB. Real-JWT smoke with two random local users passes public
create/publish retry, foreign IDs, stale draft, preferences, archive and restore.
Credentials/tokens remain only in process memory; ignored summaries/logs are not
committed. Final source restart and smoke rerun pass. The first whole-stack start
hit unavailable MinIO image; only changed services were started against existing
healthy dependencies, without changing unrelated infrastructure.

Compose config, Kustomize render, Helm lint/template pass. K8s includes an
idempotent DB Job for existing volumes. Helm is an opt-in dashboard component
assuming shared config/dependencies, not a complete-stack chart. No cluster
rollout, performance, team sharing, query/editor capability is claimed.
Current API/domain/operations, target mapping and ADR index are aligned.
Strict OpenSpec36 items and structure checks pass with existing warnings.
No hard dependency on the still-pending native zoom/speech checks in change7;
that change remains active and will gate the visual editor.