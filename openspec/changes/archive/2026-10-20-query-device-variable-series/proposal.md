# Чтение переменных и графики без лишней материализации

## Why

Сохранение переменной недостаточно: её нужно читать как обычную метрику, сохраняя source identity, единицы, ревизии и качество. Данные для каждого масштаба графика хранить отдельно не требуется.

## What Changes

- Общий measured/derived каталог и variableId/source selectors для history/latest/export.
- Bounded SQL aggregation для графиков без создания новых переменных.
- Typed dashboard owner reads и авторизованный кэш с явными границами ревизий.

## Capabilities

### New Capabilities

- `device-variable-series-queries`: Чтение переменных и графики без лишней материализации.

### Modified Capabilities

None. Existing contracts remain compatible; implementation must document additive extensions.

## Dependencies

- [materialize-device-variable-telemetry](../materialize-device-variable-telemetry/proposal.md) must be implemented and verified before activation.

## Impact

telemetry APIs/SQL/indexes, dashboard typed query contract/manifests, frontend-facing DTO compatibility. No arbitrary computation service.

## Architecture and delivery status

[Authoritative target architecture](../../../../docs/target/device-variables.md).
Proposal only: implementation tasks are open. No current runtime readiness is claimed.

