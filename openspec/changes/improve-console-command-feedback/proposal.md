# Результат команд, локальный pending и понятные ошибки

## Why

Успешное создание канала с failed list reload выглядит неуспешным сохранением; повторные row clicks могут запускать одинаковую команду; raw fetch сообщения не объясняют следующее действие.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: R14, R16, R27. Этап: UI P1.
Статус: proposal; implementation не выполнена.

## What Changes

- Раздельные mutation outcome и read refresh, canonical saved result и retry read only.
- Per-entity/action pending для Ack/Resolve/rules/channels/ML lifecycle.
- Typed user error guidance и clipboard result; recovery copy не обещает неподтверждённую доставку.

## Capabilities

### New Capabilities

- Нет нового capability; этот change добавляет уточняющие требования существующих specs.

### Modified Capabilities

- `frontend-operator-console`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.
- `account-auth-console-experience`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.

## Dependencies And Delivery

- [fix-console-evidence-state-transitions](../fix-console-evidence-state-transitions/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `frontend/src/features/notifications/`
- `frontend/src/features/alerts/`
- `frontend/src/features/ml-settings/`
- `frontend/src/api/client.ts`
- `frontend/src/components/EntitySelector.tsx`
- `frontend/src/pages/ForgotPasswordPage.tsx`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
