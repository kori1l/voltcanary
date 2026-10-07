# Готовность ML по выбранной цепочке данных

## Why

Общий pipeline сейчас считает любой matching snapshot/run/model признаком готовности, включая QUEUED/RUNNING; эти объекты могут относиться к другим dataset, scope и horizon.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: R02. Этап: UI P0.
Статус: proposal; implementation не выполнена.

## What Changes

- Ввести выбранный pipeline context account/device/datasetVersion/family/horizon/snapshot/run/model/policy.
- Различать preprocessing ready, job queued/running, evaluated candidate, approved model и effective deployment.
- Показывать причины несовместимости и missing metadata без UI-derived promotion success.

## Capabilities

### New Capabilities

- Нет нового capability; этот change добавляет уточняющие требования существующих specs.

### Modified Capabilities

- `ml-console-experience`: дополнительные сценарии и инварианты без повторной реализации существующего baseline.

## Dependencies And Delivery

- [fix-console-evidence-state-transitions](../fix-console-evidence-state-transitions/proposal.md) — prerequisite перед интеграционной приёмкой.


## Impact

- `frontend/src/features/ml-settings/model/mlConsolePresentation.ts`
- `frontend/src/features/ml-settings/hooks/`
- `frontend/src/pages/MlSettingsPage.tsx`
- `backend/services/analytics-service/`
- `docs/current/api/ml-api.md`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
