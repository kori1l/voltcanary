# Пагинация, экономный поиск и первое измерение

## Why

Devices/ML selectors ограничены первыми 100, Maintenance первыми 200; каждый символ повторяет справочники/signals. Add device закрывается до понятного подключения sender.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: R06, R07, R08, R18. Этап: UI P1.
Статус: implemented and acceptance-checked, 2026-10-08.

## What Changes

- Reachable pagination для inventory/alerts/maintenance/selectors без invented totals.
- 300ms debounce/Apply, AbortSignal, независимые справочники и bounded signal concurrency.
- Post-create activation state: registered/source configured/awaiting first sample/demo run, next actions API/MQTT.

## Capabilities

### New Capabilities

- Нет нового capability; этот change добавляет уточняющие требования существующих specs.

### Modified Capabilities

- `device-console-experience`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.
- `frontend-operator-console`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.

## Dependencies And Delivery

- [fix-console-evidence-state-transitions](../archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) — prerequisite перед интеграционной приёмкой.
- [improve-console-context-and-session-recovery](../archive/2026-10-08-improve-console-context-and-session-recovery/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `frontend/src/pages/DevicesPage.tsx`
- `frontend/src/components/EntitySelector.tsx`
- `frontend/src/maintenance/`
- `frontend/src/features/alerts/`
- `backend/services/alert-service/`
- `docs/current/api/device-api.md`
- `docs/current/api/alerts-api.md`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
