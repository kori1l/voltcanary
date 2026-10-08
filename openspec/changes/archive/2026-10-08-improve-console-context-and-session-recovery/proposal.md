# Контекст навигации, восстановление сессии и черновики

## Why

Login всегда возвращает в Devices, ML tabs и многие filters живут только в state; переход может потерять draft, assistant получает Settings вместо конкретного ML scope.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: R12, R13, R17, R28. Этап: UI P1.
Статус: proposal; implementation не выполнена.

## What Changes

- Typed URL context для ML/Devices/Alerts/Maintenance, безопасный returnTo и единый single-flight refresh.
- Route/tab draft guards и allowlisted несекретные user/account-scoped drafts.
- Context-aware assistant request summary без metadata secrets или расширения прав.

## Capabilities

### New Capabilities

- Нет нового capability; этот change добавляет уточняющие требования существующих specs.

### Modified Capabilities

- `account-auth-console-experience`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.
- `frontend-operator-console`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.
- `ai-assistant-console-experience`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.

## Dependencies And Delivery

- [fix-console-evidence-state-transitions](../archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `frontend/src/api/client.ts`
- `frontend/src/app/App.tsx`
- `frontend/src/pages/LoginPage.tsx`
- `frontend/src/aiAgent/`
- `frontend/src/features/ml-settings/`
- `frontend/src/maintenance/`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
