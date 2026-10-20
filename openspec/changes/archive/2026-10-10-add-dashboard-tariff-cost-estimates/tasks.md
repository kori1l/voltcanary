# Tasks: Версионированные тарифы и оценка стоимости энергии

Задачи завершены после implementation и verification; evidence приведён ниже.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-tariff-cost-estimates`, затем соответствующие
project surface skills. Покрытие: W14, DB-17, DB-21, DB-26, DB-34.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Зафиксировать tariff domain ownership/schema/grants и decimal rounding contract.
- [x] 1.2 Добавить versioned account tariff migrations и flat-rate effective period APIs.
- [x] 1.3 Реализовать owner/tariff.read/write access и workspace optional adapter после membership readiness.
- [x] 1.4 Определить taxes/fees/exchange exclusions и visible Estimate labels.
- [x] 1.5 Реализовать energy segment×tariff interval cost calculation с source/coverage provenance.
- [x] 1.6 Если timebands включаются, добавить timezone/DST/rate overlap validation; иначе capability остаётся off.
- [x] 1.7 Подключить W14 catalog/config/preview и missing tariff/partial/mixedcurrency states.
- [x] 1.8 Добавить immutable tariff revisions/archive и auditable backdated correction behavior.
- [x] 1.9 Проверить known energy×rate, revision boundary, missing tariff, partialenergy, rounding, mixed currency и unauthorised cost reads.
- [x] 1.10 Выполнить focused device/dashboard Maven и editor payload/visibility tests.
- [x] 1.11 Обновить business estimate docs с явным non-billing/non-invoice scope.

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

Tariff acceptance: decimal calculation tests cover segment boundaries, final HALF_EVEN rounding, missing tariff and mixed currency. Live gateway verifies independent tariff capability, immutable revisions, stale version 409 and archive preserving both historical revisions and archivedAt. Form payload tests verify decimal strings, current revision, reviewed scope and tax policy. Flat rates only; time bands, billing, fees and exchange stay unsupported.
