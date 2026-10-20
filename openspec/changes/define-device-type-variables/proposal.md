# Определения переменных типов устройств

## Why

Настройки полей не задают стабильную вычисляемую переменную с версией, единицами и наследованием. Нужен единственный control-plane владелец до изменения ingestion и хранения.

## What Changes

- Account-scoped определения OBSERVED/DERIVED и неизменяемые опубликованные ревизии в device-service.
- Привязки входов к конкретным источникам, default для типа и явный rollout с per-source outcomes.
- Атомарная проверка input retention, lifecycle/archive, optimistic concurrency и API контракт.

## Capabilities

### New Capabilities

- `device-variable-definitions`: Определения переменных типов устройств.

### Modified Capabilities

None. Existing contracts remain compatible; implementation must document additive extensions.

## Dependencies

None within this program. Existing device/source/template and telemetry contracts are the baseline.

## Impact

device-service, gateway routes, common event contract preparation, migrations and authorization. No telemetry execution yet.

## Architecture and delivery status

[Authoritative target architecture](../../../docs/target/device-variables.md).
Proposal only: implementation tasks are open. No current runtime readiness is claimed.
