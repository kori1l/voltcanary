# Достоверные состояния ресурсов и свежесть данных

## Why

При отказе API отдельные секции остаются Loading, pipeline принимает error за отсутствие prerequisites, сохранённый risk не помечается в строке, а delivery history сообщает отсутствие попыток после failed fetch.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: R01, R03, R04, R05, R09, R10, R15. Этап: UI P0.
Статус: proposal; implementation не выполнена.

## What Changes

- Расширить ресурсную модель состояниями idle/waiting-dependency и scope-bound evidence; раздельные telemetry/risk и delivery-attempt ресурсы по entity ID.
- Развести empty, unavailable, insufficient, stale и permission loss; grouped errors и локальные retry.
- Пересчитывать возраст по часам, предоставить off/30s/1m/5m auto-refresh с hidden-tab pause без мутаций.

## Capabilities

### New Capabilities

- Нет нового capability; этот change добавляет уточняющие требования существующих specs.

### Modified Capabilities

- `console-data-state-semantics`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.
- `ml-console-experience`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.
- `alert-notification-console-experience`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.

## Dependencies And Delivery

- Нет hard dependencies на другие новые changes; существующий проверенный baseline сохраняется.


## Impact

- `frontend/src/components/useSectionResource.ts`
- `frontend/src/components/SectionFeedback.tsx`
- `frontend/src/features/dashboard/`
- `frontend/src/devices/`
- `frontend/src/features/ml-settings/`
- `frontend/src/features/alerts/`
- `docs/current/flows/console-interface.md`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
