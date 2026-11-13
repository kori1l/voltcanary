# Создание личных устройств через AI с ограниченным intent contract

## Why

CREATE_DEVICE уже существует, но свободные maps и вымышленные defaults
недостаточны для надежной работы небольшой модели. Требуется законченный
сценарий создания с выбором реальных type/location и существующей авторизацией.

## What Changes

- Typed CREATE_DEVICE intent, серверный каталог типов/личных locations и понятные уточнения.
- Детерминированный prepare/review/create workflow без произвольного многошагового плана LLM.
- Durable idempotency на owning device API, повторное использование обычных правил account/location.
- Подключение источника через обычную форму после создания; credentials не проходят через LLM.
- Русскоязычные сценарии и измеряемая приемка на выбранной небольшой модели.

## Capabilities

### New Capabilities

- `ai-device-creation`: Типизированное, авторизованное и повторяемое создание устройств через AI.

### Modified Capabilities

None. Existing device creation and assistant review contracts remain authoritative.

## Dependencies

- [isolate-ai-creation-execution](../2026-10-20-isolate-ai-creation-execution/proposal.md).
- [harden-user-identity-boundary](../2026-10-20-harden-user-identity-boundary/proposal.md).

## Impact

AI intent/compiler and device adapter, device-service idempotency migration,
focused assistant UI and device setup navigation. No new permission engine,
workspace creation, bulk import, deletion, implicit simulator start or hardware commands.

## Status

Implemented and verified on 2026-10-21; evidence is recorded in the current AI creation verification flow.



