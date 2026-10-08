# Исторические метрики, значимые точки и доступные графики

## Why

Запрос исторических chart keys зависит от последнего reading; uniform downsampling сохраняет один global maximum, но может скрыть trough и другие spikes; SVG не обеспечивает чтение значений без pointer.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: R19, R20, R23. Этап: UI P1.
Статус: implemented and acceptance-checked, 2026-10-08.

## What Changes

- Range-aware metric discovery с additive API при необходимости, независимо от latest.
- Min/max bucket envelope, threshold-crossing/gap preservation и raw/display point counts.
- Text summary и cursor-paginated values/event evidence без hover-only доступа.

## Capabilities

### New Capabilities

- Нет нового capability; этот change добавляет уточняющие требования существующих specs.

### Modified Capabilities

- `telemetry-console-experience`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.

## Dependencies And Delivery

- [fix-console-evidence-state-transitions](../archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `frontend/src/devices/details/useDeviceTelemetryWorkspace.ts`
- `frontend/src/features/telemetry/`
- `backend/services/telemetry-service/`
- `docs/current/api/telemetry-api.md`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
