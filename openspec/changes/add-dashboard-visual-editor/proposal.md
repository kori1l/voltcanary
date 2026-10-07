# Визуальный редактор и персональный стартовый экран

## Why

Пользователь не может создавать и сохранять рабочие представления; обычные USER не видят Dashboard navigation. Редактор без отдельного view/draft режима приведёт к случайным изменениям.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: DB-01, DB-02, DB-03, DB-05, DB-06, DB-07, DB-08, DB-09, DB-10, DB-11, DB-12, DB-13, DB-14, DB-15, DB-22, DB-28, DB-29, DB-30, DB-31, DB-32, DB-43. Этап: Dashboards 1.
Статус: proposal; implementation не выполнена.

## What Changes

- My/Favorites listing, creation blank/copy, accessible USER entry и safe default fallback.
- Typed widget configure/preview, sections/layout/undo/redo и раздельные View/Edit/Publish.
- Server draft autosave, conflict recovery, URL runtime context, mobile layout и контролируемый refresh.

## Capabilities

### New Capabilities

- `dashboard-editor-experience`: Визуальный редактор и персональный стартовый экран.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- [add-private-dashboard-domain](../add-private-dashboard-domain/proposal.md) — prerequisite перед интеграционной приёмкой.
- [add-dashboard-widget-query-engine](../add-dashboard-widget-query-engine/proposal.md) — prerequisite перед интеграционной приёмкой.
- [improve-console-context-and-session-recovery](../improve-console-context-and-session-recovery/proposal.md) — prerequisite перед интеграционной приёмкой.
- [improve-console-accessibility-and-page-delivery](../improve-console-accessibility-and-page-delivery/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `frontend/src/features/dashboards/`
- `frontend/src/pages/DashboardPage.tsx`
- `frontend/src/app/App.tsx`
- `frontend/src/app/AppLayout.tsx`
- `frontend/src/api/`
- `docs/current/flows/ (future dashboard verification)`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
