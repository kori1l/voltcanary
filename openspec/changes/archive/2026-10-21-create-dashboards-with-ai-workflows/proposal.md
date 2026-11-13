# Приватные дашборды из AI intent и существующих шаблонов

## Why

Dashboard-service уже имеет template catalogue, validation, binding access и
idempotent creation. AI пока не умеет создавать dashboards. Небольшая модель
должна выбирать задачу и bindings, а не конструировать полную definition/layout.

## What Changes

- Typed CREATE_DASHBOARD intent с реальными template/device/source/metric refs.
- Серверная компиляция существующих шаблонов и проверка совместимости/прав без новой роли AI.
- Read-only preview, подтверждение и создание приватного draft через обычный dashboard API.
- Отсутствующие данные и метрики показаны явно; publication/sharing не выполняются автоматически.
- Совместимый сценарий «создать устройство, затем предложить дашборд» как отдельные подтверждаемые задачи.

## Capabilities

### New Capabilities

- `ai-dashboard-creation`: Создание приватных дашбордов из проверенного intent и шаблонов.

### Modified Capabilities

None. Existing dashboard, widget query, binding and sharing policies are reused.

## Dependencies

- [isolate-ai-creation-execution](../2026-10-20-isolate-ai-creation-execution/proposal.md).
- [harden-user-identity-boundary](../2026-10-20-harden-user-identity-boundary/proposal.md).
- [create-devices-with-ai-workflows](../2026-10-20-create-devices-with-ai-workflows/proposal.md) only for the optional newly-created-device handoff; existing-device dashboard creation does not depend on it.

## Impact

AI dashboard adapter/compiler, optional focused template assembly endpoint in
dashboard-service, assistant UI and existing dashboard routes. No new dashboard
engine, workspace publishing, arbitrary model-generated code/layout, ML change
or automatic default-dashboard replacement.

## Status

Implemented and verified in Docker with real PostgreSQL, frozen small-model
evaluation (19/20 clear, 20/20 unresolved), restart/receipt recovery and browser
create-open-cleanup acceptance. See [verification](../../../../docs/current/flows/ai-creation-verification.md).


