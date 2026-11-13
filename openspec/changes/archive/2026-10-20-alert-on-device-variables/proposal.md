# Пороговые тревоги по переменным устройств

## Why

Новая числовая переменная должна быть доступна для тревог без фиксированного списка метрик, подмены ML-риском и вычисления условий по прореженному графику.

## What Changes

- Rule references variableId/source/unit в существующем alert-service.
- Идемпотентная LIVE evaluation ветка analytics по accepted measurements и полное evidence.
- Совместимость anomaly events, maintenance/cooldown/dedup и dry run.

## Capabilities

### New Capabilities

- `device-variable-threshold-alerts`: Пороговые тревоги по переменным устройств.

### Modified Capabilities

None. Existing contracts remain compatible; implementation must document additive extensions.

## Dependencies

- [materialize-device-variable-telemetry](../materialize-device-variable-telemetry/proposal.md) must be implemented and verified before activation.
- [query-device-variable-series](../query-device-variable-series/proposal.md) must be implemented and verified before activation.

## Impact

alert-service rule schema/API/validation, analytics consumer/evaluator/outbox, anomaly evidence contract; existing notification module stays owner.

## Architecture and delivery status

[Authoritative target architecture](../../../../docs/target/device-variables.md).
Proposal only: implementation tasks are open. No current runtime readiness is claimed.

