# Интерфейс переменных и сквозная приёмка

## Why

Архитектура полезна только как законченный пользовательский механизм: выбрать поле, увидеть преобразование, включить на источнике, построить график и настроить тревогу с понятным состоянием.

## What Changes

- Редактор переменной типа с простыми операциями, bounded expression mode, preview и source bindings.
- Отображение реального desired/applied generation и ошибок без ложной готовности.
- Общие metric selectors в telemetry/dashboard/rules, сквозная приёмка и эксплуатационные ограничения.

## Capabilities

### New Capabilities

- `device-variable-console`: Интерфейс переменных и сквозная приёмка.

### Modified Capabilities

None. Existing contracts remain compatible; implementation must document additive extensions.

## Dependencies

- [define-device-type-variables](../define-device-type-variables/proposal.md) must be implemented and verified before activation.
- [propagate-variable-processing-contracts](../propagate-variable-processing-contracts/proposal.md) must be implemented and verified before activation.
- [materialize-device-variable-telemetry](../materialize-device-variable-telemetry/proposal.md) must be implemented and verified before activation.
- [query-device-variable-series](../query-device-variable-series/proposal.md) must be implemented and verified before activation.
- [alert-on-device-variables](../alert-on-device-variables/proposal.md) must be implemented and verified before activation.

## Impact

frontend focused feature modules and existing APIs; cross-service verification/runbook/metrics, no production capacity claim or new cloud dependency.

## Architecture and delivery status

[Authoritative target architecture](../../../docs/target/device-variables.md).
Proposal only: implementation tasks are open. No current runtime readiness is claimed.
