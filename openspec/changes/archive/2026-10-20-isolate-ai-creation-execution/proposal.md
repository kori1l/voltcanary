# Изолированные и надежные AI creation tasks

## Why

AI уже передает пользовательский JWT доменным APIs; копировать ownership/RBAC
в AI не требуется. Но PlanStore хранит планы в памяти, plan owner ограничен
userId, execution не имеет durable состояния и защиты от повторных вызовов.
Контекст из браузера не должен становиться доверенной областью доступа.

## What Changes

- Серверный actor/account/task scope, безопасный контекст и resource refs, изоляция plan/results/cache/provider selection.
- Durable versioned plans, scoped lookup, подтверждение конкретного payload, execution claim и результаты шагов.
- Domain adapters вызывают обычные APIs с JWT пользователя; ни глобальной роли AI, ни отдельной копии доменной политики.
- Typed creation intents, NEEDS_INPUT, ограничение размера/действий и совместимость прежних разрешенных инструментов.
- Контракт idempotency/unknown outcomes для последующих device/dashboard changes.

## Capabilities

### New Capabilities

- `ai-creation-execution`: Изоляция, типизированное планирование и надежное исполнение AI creation tasks.

### Modified Capabilities

None. Existing assistant review and domain authorization remain in force.

## Dependencies

- [harden-user-identity-boundary](../2026-10-20-harden-user-identity-boundary/proposal.md): release prerequisite; actor identity must be verified on downstream APIs.

## Impact

AI service migrations/API/DTO/adapters, focused assistant UI, tests and current
API/runbook updates. No authorization engine, autonomous background credentials,
new workspace permissions, RAG, ML lifecycle or model-provider expansion.

## Status

Proposal only; implementation tasks are open.

