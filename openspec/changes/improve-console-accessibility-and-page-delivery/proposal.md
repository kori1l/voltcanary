# Доступность, мобильные рабочие экраны и загрузка по маршрутам

## Why

ML selected tab обозначается только CSS, Agenda содержит nested actions в button-role контейнере, required marker переносится строкой; общий bundle загружает ML/Admin для login.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: R11, R21, R22, R24, R25, R26. Этап: UI P1/P2.
Статус: proposal; implementation не выполнена.

## What Changes

- Общий accessible Tabs, status/alert announcements, named entity actions и semantic CSS tokens.
- Task-first navigation/terminology и адаптивные table/card/section selectors по проверенному прототипу.
- Route lazy loading с stable skeleton и chunk-failure recovery, без повышения ролей.

## Capabilities

### New Capabilities

- Нет нового capability; этот change добавляет уточняющие требования существующих specs.

### Modified Capabilities

- `console-design-system`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.
- `frontend-operator-console`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.

## Dependencies And Delivery

- [fix-console-evidence-state-transitions](../fix-console-evidence-state-transitions/proposal.md) — prerequisite перед интеграционной приёмкой.
- [improve-console-command-feedback](../improve-console-command-feedback/proposal.md) — prerequisite перед интеграционной приёмкой.
- [improve-console-context-and-session-recovery](../improve-console-context-and-session-recovery/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `frontend/src/components/`
- `frontend/src/app/App.tsx`
- `frontend/src/app/AppLayout.tsx`
- `frontend/src/app/styles.css`
- `frontend/src/app/console.css`
- `frontend/src/maintenance/MaintenanceAgenda.tsx`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
