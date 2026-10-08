# Workspace, участники и отдельные права на ресурсы

## Why

Текущие accounts принадлежат отдельным пользователям; team dashboards нельзя безопасно построить на переименовании USER/ADMIN или копировании owner JWT.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: DB-33, DB-34, DB-35, DB-36, DB-37. Этап: Dashboards 2 prerequisite.
Статус: proposal; implementation не выполнена.

## What Changes

- Workspace membership/teams и explicit resource grants с поддержкой существующих personal accounts.
- Read authorization для device/telemetry/analytics/alerts/maintenance без spoofed headers или global role bypass.
- Revocation/lifecycle/audit/grants fingerprint и минимальный интерфейс membership management.

## Capabilities

### New Capabilities

- `workspace-resource-membership`: Workspace, участники и отдельные права на ресурсы.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- [add-private-dashboard-domain](../archive/2026-10-08-add-private-dashboard-domain/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `backend/services/identity-service/`
- `backend/services/device-service/`
- `backend/common/common-security/`
- `backend/services/telemetry-service/`
- `backend/services/analytics-service/`
- `backend/services/alert-service/`
- `frontend/src/features/workspaces/ (new)`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
