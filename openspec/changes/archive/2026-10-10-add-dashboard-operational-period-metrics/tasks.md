# Tasks: Рабочие расписания, подтверждённые циклы и простой

Задачи завершены после implementation и verification; evidence приведён ниже.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-operational-period-metrics`, затем соответствующие
project surface skills. Покрытие: W15, DB-17, DB-20, DB-21, DB-26, DB-27.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Определить operating schedule/operational fact ownership и source authorization contract без telemetry whitelist расширения.
- [x] 1.2 Добавить versioned timezone/weekslots/holidays/effective interval schema и validation.
- [x] 1.3 Определить cycle/downtime fact enums/identities/completeness/correction audit и accessible evidence references.
- [x] 1.4 Реализовать idempotent fact create/import/read и explicit correction reasons без auto-peak cycle inference.
- [x] 1.5 Реализовать DST-aware scheduled intervals и downtime union без duplicate overlap count.
- [x] 1.6 Реализовать qualified energy-per-confirmed-cycle и uptime denominators с partial/aborted/unknown states.
- [x] 1.7 Подключить W15 catalog и schedule-normalized comparison capability только после verified fact coverage.
- [x] 1.8 Добавить task presets для laundry/workshop без заявлений orders/OEE/application availability.
- [x] 1.9 Покрыть duplicate fact, missing end, correction, DST holiday, overlapping downtime и energy boundary gaps.
- [x] 1.10 Проверить отсутствие invented cycles от power spike и отсутствие inferred downtime от missing sample.
- [x] 1.11 Выполнить focused device/telemetry/dashboard tests, event/import auth integration и docs/contracts.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Проверить Dashboards → /dashboards, sidebar links only, catalog item actions/menu visibility, numeric grid profiles/flexible panel sizes, named screens, placement, right drawer, скрываемую навигацию, minimal defaults, optional annotations и обязательные compact exceptional states; выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [x] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.

Acceptance checkpoint (2026-10-10): implementation, current API/ADR documentation
and dependencies verified. Full backend `mvn -f backend/pom.xml test -q` completed
with exit 0: 454 tests, zero failures/errors/skips. Full frontend typecheck,
85 files / 265 tests and production build passed; ML unittest 62 tests and live
Docker training/artifact/inference smoke passed. Strict OpenSpec 47/47 and
structure checks passed before archive. Commands and scope are recorded in
`docs/current/flows/workspace-dashboard-business-verification.md`.
Generated logs remain local in ignored `artifacts/final-*` files.
Limitations: one passing Kafka test JVM required Surefire shutdown termination
after System.exit(0); existing structure/deprecation warnings remain. Local
synthetic energy profiling is not a production capacity claim. Native speech,
physical pointer drag and OS zoom are not claimed as newly exercised here.

Operations acceptance: confirmed-event gateway smoke covers registered source authorization, idempotent create/import, conflicts, correction and incomplete facts. Additional smoke verifies atomic rollback with a foreign source. Calculation tests verify DST spring gaps/fall overlaps, holidays, downtime union, missing observation, contained completed cycles, aborted/incomplete counts and partial boundary energy. Empty facts never read power or infer cycles. UI verifies preserved schedule timezone, explicit incomplete end and stale history rejection; compact summaries retain exceptional states.
