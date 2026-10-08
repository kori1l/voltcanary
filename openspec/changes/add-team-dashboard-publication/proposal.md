# Командные дашборды, публикация и совместный доступ

## Why

Общий dashboard полезен смене и филиалам, но доступ к представлению не должен выдавать доступ к устройствам; личные filters не должны менять командную published version.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: DB-02, DB-05, DB-10, DB-11, DB-22, DB-31, DB-34, DB-35, DB-36, DB-37, W11, W12. Этап: Dashboards 2.
Статус: proposal; implementation не выполнена.

## What Changes

- Workspace dashboard grants view/edit/publish/manage-sharing/archive и audited ownership.
- Sharing preview и per-viewer subset evidence без утечки counts/names недоступных ресурсов.
- Team tab, shared publish revisions, personal preferences, safe copy/export/rebind и authorised kiosk.

## Capabilities

### New Capabilities

- `team-dashboard-publication`: Командные дашборды, публикация и совместный доступ.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- [add-workspace-resource-membership](../add-workspace-resource-membership/proposal.md) — prerequisite перед интеграционной приёмкой.
- [add-private-dashboard-domain](../archive/2026-10-08-add-private-dashboard-domain/proposal.md) — prerequisite перед интеграционной приёмкой.
- [add-dashboard-widget-query-engine](../archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) — prerequisite перед интеграционной приёмкой.
- [add-dashboard-visual-editor](../add-dashboard-visual-editor/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `backend/services/dashboard-service/`
- `backend/services/identity-service/`
- `backend/services/device-service/`
- `frontend/src/features/dashboards/`
- `docs/current/api/ (future sharing contract)`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
