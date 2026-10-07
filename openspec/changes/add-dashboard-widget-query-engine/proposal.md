# Типизированные запросы виджетов и полные агрегаты

## Why

Виджетам нужен общий resolved scope/time, полнота парка и typed business intent; композиция frontend из bounded lists даёт ложные totals и uncontrolled request fanout.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: DB-16, DB-17, DB-18, DB-19, DB-20, DB-21, DB-23, DB-24, DB-26, DB-27, DB-28, DB-29, DB-30, DB-31, DB-32, DB-39, DB-40, DB-41, DB-42, DB-43, W01, W02, W03, W04, W07, W08, W09, W10, W11, W12, W13. Этап: Dashboards 1.
Статус: proposal; implementation не выполнена.

## What Changes

- Manifest catalog/typeVersion и POST /dashboards/{id}/query для разрешённого published/draft definition.
- Authorised scope resolver, interval/timezone/compare/reducer provenance и per-panel partial errors.
- Batch read ports, independent full-count aggregates, safe caching, resource budgets и read-only refresh.

## Capabilities

### New Capabilities

- `dashboard-widget-queries`: Типизированные запросы виджетов и полные агрегаты.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- [add-private-dashboard-domain](../add-private-dashboard-domain/proposal.md) — prerequisite перед интеграционной приёмкой.
- [fix-console-evidence-state-transitions](../fix-console-evidence-state-transitions/proposal.md) — prerequisite перед интеграционной приёмкой.
- [improve-telemetry-history-and-readable-evidence](../improve-telemetry-history-and-readable-evidence/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `backend/services/dashboard-service/`
- `backend/services/device-service/`
- `backend/services/telemetry-service/`
- `backend/services/analytics-service/`
- `backend/services/alert-service/`
- `frontend/src/features/dashboards/ (future client)`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
