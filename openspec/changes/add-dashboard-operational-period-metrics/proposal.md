# Рабочие расписания, подтверждённые циклы и простой

## Why

Прачечной/мастерской нужны энергия на цикл и downtime, но spikes мощности не являются заказами, а пропуск telemetry не доказывает остановку процесса. Рабочие/нерабочие часы пока не имеют domain contract.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: W15, DB-17, DB-20, DB-21, DB-26, DB-27. Этап: Dashboards 3.
Статус: proposal; implementation не выполнена.

## What Changes

- Versioned operating schedule/timezone/holidays для authorised locations и explicit cycle/downtime facts.
- Idempotent source facts/corrections, trustworthy overlap/window completeness и event ownership.
- W15 и schedule-normalized comparison только по подтверждённым semantics, independent from online freshness.

## Capabilities

### New Capabilities

- `dashboard-operational-metrics`: Рабочие расписания, подтверждённые циклы и простой.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- [add-dashboard-widget-query-engine](../archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) — prerequisite перед интеграционной приёмкой.
- [add-dashboard-energy-aggregates](../add-dashboard-energy-aggregates/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `backend/services/device-service/ (operational facts/schedules)`
- `backend/services/dashboard-service/`
- `backend/services/telemetry-service/ (qualified window reads)`
- `frontend/src/features/dashboards/`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
