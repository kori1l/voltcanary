# Tasks: Серверные личные дашборды, версии и предпочтения

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-private-dashboard-domain`, затем соответствующие
project surface skills. Покрытие: DB-08, DB-09, DB-10, DB-12, DB-22, DB-33, DB-38, DB-39, DB-40, DB-43.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Зафиксировать ADR Dashboard domain/service8089/database и port/read ownership boundaries.
- [ ] 1.2 Определить DTO/schema validation, secret/code rejection, widget payload bounds и consistent error statuses.
- [ ] 1.3 Добавить module, database initialization/Flyway, health/readiness и idempotency/revision/preferences storage.
- [ ] 1.4 Реализовать private CRUD/cursor list и server-side active account authorization.
- [ ] 1.5 Реализовать author draft ETag, atomic publish/revisions/rollback-as-new-draft и409 conflict.
- [ ] 1.6 Реализовать body-bound idempotency24h с permission recheck при replay.
- [ ] 1.7 Реализовать copy без ACL, archive/restore и preference fallback после archive/noaccess.
- [ ] 1.8 Добавить gateway routes, Compose/Kubernetes/Helm wiring согласованно с принятой ADR.
- [ ] 1.9 Покрыть cross-user IDs/history/drafts, supplied ownership spoof, quota, double create/publish и conflict.
- [ ] 1.10 Покрыть unavailable account, disable user, archive default, restore ID и unknown widget schema preservation.
- [ ] 1.11 Выполнить focused dashboard/gateway Maven tests, Flyway fresh/upgrade и local health/auth smoke.
- [ ] 1.12 Обновить current dashboard API/domain docs только после implementation; проверить structure/OpenSpec и инфраструктуру.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить focused Maven/migration/authorization integration проверки затронутых сервисов; не принимать readiness по одному health response.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
