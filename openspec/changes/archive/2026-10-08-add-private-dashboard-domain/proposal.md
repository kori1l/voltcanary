# Серверные личные дашборды, версии и предпочтения

## Why

Текущий Dashboard фиксированный; server definitions/drafts/revisions/preferences отсутствуют. Browser-only JSON не даёт изоляции, восстановления между сессиями и безопасного publish.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: DB-08, DB-09, DB-10, DB-12, DB-22, DB-33, DB-38, DB-39, DB-40, DB-43. Этап: Dashboards 1.
Статус: implemented; local domain, ownership/concurrency, migration, gateway and Compose smoke verified 2026-10-08.

## What Changes

- Добавить отдельный Dashboard domain с private account ownership, typed schema, draft/publish/revisions/archive/restore/copy.
- Additive gateway API с ETag, optimistic concurrency, идемпотентностью create/publish и server preferences.
- Добавить focused dashboard-service candidate, Flyway, readiness и local deployment без изменения существующих device/ML contracts.

## Capabilities

### New Capabilities

- `private-dashboard-domain`: Серверные личные дашборды, версии и предпочтения.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- Нет hard dependencies на другие новые changes; существующий проверенный baseline сохраняется.


## Impact

- `backend/services/dashboard-service/ (new)`
- `backend/pom.xml`
- `backend/services/api-gateway/`
- `deploy/`
- `docker-compose.yml`
- `docs/current/api/ (future dashboard contract)`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
