# Расход энергии, покрытие и защита от двойного счёта

## Why

energyWh — cumulative meter reading. Сумма readings или разница после reset может создать ложный расход; power integration через gap и parent/submeter sum также искажает KPI.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: DB-21, DB-23, DB-25, DB-26, DB-27, W05, W06. Этап: Dashboards 1 extension.
Статус: proposal; implementation не выполнена.

## What Changes

- COUNTER_DELTA/POWER_INTEGRAL методы с canonical meter/source identity, reset/gap/boundary policy и coverage.
- Полные authorised energy/rank/location aggregates, min/max power и comparison provenance.
- Metering independence/topology и Energy template capability; raw consumption не исключает фактическое обслуживание.

## Capabilities

### New Capabilities

- `dashboard-energy-aggregates`: Расход энергии, покрытие и защита от двойного счёта.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- [add-dashboard-widget-query-engine](../add-dashboard-widget-query-engine/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `backend/services/telemetry-service/`
- `backend/services/device-service/`
- `backend/services/dashboard-service/`
- `frontend/src/features/dashboards/`
- `docs/current/api/telemetry-api.md`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
