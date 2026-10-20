# Tasks: Расход энергии, покрытие и защита от двойного счёта

Задачи завершены после implementation и verification; evidence приведён ниже.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-energy-aggregates`, затем соответствующие
project surface skills. Покрытие: DB-21, DB-23, DB-25, DB-26, DB-27, W05, W06.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Определить energy API/query DTO и method/provenance/coverage semantics включая interval boundaries.
- [x] 1.2 Добавить registry physical meter identity/counter reset/replacement metadata и permissions.
- [x] 1.3 Реализовать COUNTER_DELTA по сегментам с explicit reset/rollover/missing-boundary handling.
- [x] 1.4 Реализовать POWER_INTEGRAL full readings с time-weighting и excluded gap intervals, estimate labels.
- [x] 1.5 Нормализовать duplicate/outoforder/quality source data без fabricated zero.
- [x] 1.6 Добавить independent meter validation; если topology включена — acyclic parent/submeter graph и anti-doublecount guard.
- [x] 1.7 Добавить full-scope kWh/peak ranking/location totals и partial evaluated coverage.
- [x] 1.8 Подключить W05/W06 adapter и gated Energy template, comparison absolute/undefinedpercent при base0.
- [x] 1.9 Покрыть10000→10500Wh, counter reset/replacement,2h1000W, gaps, mixed units, duplicate physical meter и maintenance raw energy.
- [x] 1.10 Выполнить telemetry/device/dashboard Maven и aggregation API isolation tests.
- [x] 1.11 Измерить representative30d scope profiles/retention limits и обновить unit/provenance/API docs.

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

Energy acceptance: calculator tests cover counter boundaries/reset/rollover/replacement, uneven power integration, quality and gaps. Meter registry tests cover physical identity/topology and ownership. The live 30-day profile used 12 devices / 69,144 samples: 8,640 kWh COMPLETE, two location totals, both methods, zero baseline absolute delta 0.5 kWh and unavailable percentage. Current run warm P95 307.342 ms under concurrent local tests; cold queries 1.640–2.290 s. Bounded reads: 31 days / 500,000 samples; retention unchanged.
