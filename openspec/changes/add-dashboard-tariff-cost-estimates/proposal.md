# Версионированные тарифы и оценка стоимости энергии

## Why

Владельцу нужен расход в деньгах, но current tariff/currency contracts отсутствуют. Умножение неполной энергии на arbitrary price создаёт ложный bill и экономию.

Источник: [UI/UX audit](../../../docs/target/ui-design-requirements.md) и
[custom dashboard requirements](../../../docs/target/custom-dashboards.md).
Покрытие: W14, DB-17, DB-21, DB-26, DB-34. Этап: Dashboards 3.
Статус: proposal; implementation не выполнена.

## What Changes

- Account/workspace tariff entity: version, currency, effective interval/timezone, explicit tax/fee policy и tariff grants.
- Estimated cost по qualified energy segments/rates, missing-tariff/mixed-currency/partial states.
- W14 manifest/editor с Estimate provenance, без billing/invoice или автоматических закупок.

## Capabilities

### New Capabilities

- `dashboard-tariff-estimates`: Версионированные тарифы и оценка стоимости энергии.

### Modified Capabilities

- Existing capability semantics не заменяются; новые контракты добавляются отдельно.

## Dependencies And Delivery

- [add-dashboard-energy-aggregates](../add-dashboard-energy-aggregates/proposal.md) — prerequisite перед интеграционной приёмкой.
- [add-dashboard-visual-editor](../archive/2026-10-09-add-dashboard-visual-editor/proposal.md) — prerequisite перед интеграционной приёмкой.

Workspace тарифы additionally требуют [add-workspace-resource-membership](../add-workspace-resource-membership/proposal.md); личные account тарифы этого не требуют.

## Presentation compatibility

This extension preserves named working screens, explicit placement and minimal
visualization defaults; the shared UI baseline and evidence disclosures are
specified in [design.md](design.md#dashboard-presentation-baseline).

## Impact

- `backend/services/device-service/ (energy tariff domain)`
- `backend/services/dashboard-service/`
- `frontend/src/features/dashboards/`
- `docs/current/api/ (future tariff contract)`

Новые API/таблицы/permissions существуют только после implementation и проверки.
Старые owner-scoped API, подтверждения действий, секреты и canonical IDs сохраняют
свой контракт. Detail decisions, failure modes и compatibility перечислены в design.md.

## Out Of Scope

Этот change не реализует остальные stages, не переписывает archived UI baseline,
не отмечает чужие tasks выполненными и не публикует feature без acceptance.
Public anonymous dashboards, arbitrary user code/SQL, billing/payments и marketing
редизайн не входят в этот пакет.
