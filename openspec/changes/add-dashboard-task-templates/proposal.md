# Бизнес-шаблоны и привязка к реальным данным

## Why

Гибкий blank editor требует знаний метрик; шаблоны должны решать задачи смены/техника/владельца, не подставлять чужие ID или выдуманные temperature/energy/cycles.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: DB-03, DB-04, DB-05, DB-16, DB-17, DB-18, DB-22, W01, W02, W03, W04, W05, W06, W07, W08, W09, W10, W12, W13. Этап: Dashboards 1.
Статус: proposal; implementation не выполнена.

## What Changes

- Versioned task/persona templates с symbolic bindings и dependency/capability preview.
- Пять шаблонов: Смена, Мои устройства, Холодильное оборудование, Техник и Energy после aggregate capability.
- Business guidance для восьми сегментов без изменения authorization roles.

## Capabilities

### New Capabilities

- `dashboard-task-templates`: Бизнес-шаблоны и привязка к реальным данным.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- [add-dashboard-visual-editor](../add-dashboard-visual-editor/proposal.md) — prerequisite перед интеграционной приёмкой.
- [add-dashboard-widget-query-engine](../archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) — prerequisite перед интеграционной приёмкой.

Energy template имеет capability gate на [add-dashboard-energy-aggregates](../add-dashboard-energy-aggregates/proposal.md), не hard dependency личного первого релиза.

## Impact

- `backend/services/dashboard-service/ (template catalog)`
- `frontend/src/features/dashboards/ (catalog/bindings)`
- `docs/target/custom-dashboards.md`
- `docs/current/flows/ (future persona verification)`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
