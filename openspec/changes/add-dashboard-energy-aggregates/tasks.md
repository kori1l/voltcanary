# Tasks: Расход энергии, покрытие и защита от двойного счёта

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-energy-aggregates`, затем соответствующие
project surface skills. Покрытие: DB-21, DB-23, DB-25, DB-26, DB-27, W05, W06.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Определить energy API/query DTO и method/provenance/coverage semantics включая interval boundaries.
- [ ] 1.2 Добавить registry physical meter identity/counter reset/replacement metadata и permissions.
- [ ] 1.3 Реализовать COUNTER_DELTA по сегментам с explicit reset/rollover/missing-boundary handling.
- [ ] 1.4 Реализовать POWER_INTEGRAL full readings с time-weighting и excluded gap intervals, estimate labels.
- [ ] 1.5 Нормализовать duplicate/outoforder/quality source data без fabricated zero.
- [ ] 1.6 Добавить independent meter validation; если topology включена — acyclic parent/submeter graph и anti-doublecount guard.
- [ ] 1.7 Добавить full-scope kWh/peak ranking/location totals и partial evaluated coverage.
- [ ] 1.8 Подключить W05/W06 adapter и gated Energy template, comparison absolute/undefinedpercent при base0.
- [ ] 1.9 Покрыть10000→10500Wh, counter reset/replacement,2h1000W, gaps, mixed units, duplicate physical meter и maintenance raw energy.
- [ ] 1.10 Выполнить telemetry/device/dashboard Maven и aggregation API isolation tests.
- [ ] 1.11 Измерить representative30d scope profiles/retention limits и обновить unit/provenance/API docs.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Проверить Dashboards → /dashboards, sidebar links only, catalog item actions/menu visibility, numeric grid profiles/flexible panel sizes, named screens, placement, right drawer, скрываемую навигацию, minimal defaults, optional annotations и обязательные compact exceptional states; выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
