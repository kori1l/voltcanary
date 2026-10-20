# Версионированные контракты обработки источников

## Why

Формулы нельзя выбирать по latest-конфигурации после доставки сообщения: задержки, рестарты и повторная доставка дали бы разные результаты. Входы также должны пережить существующую фильтрацию полей.

## What Changes

- Secret-free compacted source.processing-config.v1 и восстановимые локальные проекции.
- Additive processingContext принятого сообщения с pinned generation/digest.
- Динамические числовые входы и совместимость source auth/retention/bundle без синхронного lookup на каждое сообщение.

## Capabilities

### New Capabilities

- `variable-processing-contracts`: Версионированные контракты обработки источников.

### Modified Capabilities

None. Existing contracts remain compatible; implementation must document additive extensions.

## Dependencies

- [define-device-type-variables](../define-device-type-variables/proposal.md) must be implemented and verified before activation.

## Impact

device-service outbox/reconciliation, ingestion-service projections/pipeline, telemetry projection, common-events compatibility and topic provisioning.

## Architecture and delivery status

[Authoritative target architecture](../../../docs/target/device-variables.md).
Proposal only: implementation tasks are open. No current runtime readiness is claimed.
