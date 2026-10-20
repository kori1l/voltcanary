# Вычисление и хранение переменных в телеметрии

## Why

Переменная должна стать настоящим числовым рядом с происхождением и качеством, а не JSON-полем или отдельной таблицей, которую не видят потребители.

## What Changes

- Чистый ограниченный evaluator в telemetry и общий runtime/preview путь.
- Observed custom inputs и derived outputs в общей модели измерений с immutable lineage.
- Атомарное сохранение результатов/ошибок/outbox и новый measurement event для потребителей переменных.

## Capabilities

### New Capabilities

- `materialized-device-variables`: Вычисление и хранение переменных в телеметрии.

### Modified Capabilities

None. Existing contracts remain compatible; implementation must document additive extensions.

## Dependencies

- [define-device-type-variables](../define-device-type-variables/proposal.md) must be implemented and verified before activation.
- [propagate-variable-processing-contracts](../propagate-variable-processing-contracts/proposal.md) must be implemented and verified before activation.

## Impact

telemetry-service domain/application/adapters, PostgreSQL/Timescale and H2 migrations, accepted measurement event and legacy compatibility.

## Architecture and delivery status

[Authoritative target architecture](../../../docs/target/device-variables.md).
Proposal only: implementation tasks are open. No current runtime readiness is claimed.
