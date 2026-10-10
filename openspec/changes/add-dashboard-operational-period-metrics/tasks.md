# Tasks: Рабочие расписания, подтверждённые циклы и простой

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-operational-period-metrics`, затем соответствующие
project surface skills. Покрытие: W15, DB-17, DB-20, DB-21, DB-26, DB-27.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Определить operating schedule/operational fact ownership и source authorization contract без telemetry whitelist расширения.
- [ ] 1.2 Добавить versioned timezone/weekslots/holidays/effective interval schema и validation.
- [ ] 1.3 Определить cycle/downtime fact enums/identities/completeness/correction audit и accessible evidence references.
- [ ] 1.4 Реализовать idempotent fact create/import/read и explicit correction reasons без auto-peak cycle inference.
- [ ] 1.5 Реализовать DST-aware scheduled intervals и downtime union без duplicate overlap count.
- [ ] 1.6 Реализовать qualified energy-per-confirmed-cycle и uptime denominators с partial/aborted/unknown states.
- [ ] 1.7 Подключить W15 catalog и schedule-normalized comparison capability только после verified fact coverage.
- [ ] 1.8 Добавить task presets для laundry/workshop без заявлений orders/OEE/application availability.
- [ ] 1.9 Покрыть duplicate fact, missing end, correction, DST holiday, overlapping downtime и energy boundary gaps.
- [ ] 1.10 Проверить отсутствие invented cycles от power spike и отсутствие inferred downtime от missing sample.
- [ ] 1.11 Выполнить focused device/telemetry/dashboard tests, event/import auth integration и docs/contracts.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Проверить Dashboards → /dashboards, sidebar links only, catalog item actions/menu visibility, numeric grid profiles/flexible panel sizes, named screens, placement, right drawer, скрываемую навигацию, minimal defaults, optional annotations и обязательные compact exceptional states; выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
