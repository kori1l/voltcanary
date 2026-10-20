# Tasks: Командные дашборды, публикация и совместный доступ

Задачи завершены после implementation и verification; evidence приведён ниже.
Перед кодом: `npm.cmd run openspec:apply -- add-team-dashboard-publication`, затем соответствующие
project surface skills. Покрытие: DB-02, DB-05, DB-10, DB-11, DB-22, DB-31, DB-34, DB-35, DB-36, DB-37, W11, W12.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Определить dashboard grant matrix и view/copy/history/export policies отдельно от resource grants.
- [x] 1.2 Добавить workspace dashboard ownership/grant/audit migrations с private v1 compatibility.
- [x] 1.3 Реализовать grants read/write и recipient/team active membership checks.
- [x] 1.4 Реализовать safe sharing-preview без назначения resource access и hidden names/counts.
- [x] 1.5 Добавить Team listing/search metadata только разрешённых objects и minimal audience UI.
- [x] 1.6 Добавить shared author drafts, publish-only grant и409 conflict recovery.
- [x] 1.7 Применить per-viewer scope intersection/provenance и invalidate cache on grants/resource revoke.
- [x] 1.8 Добавить safe copy/export/import placeholder rebind без ACL/telemetry/secrets.
- [x] 1.9 Добавить owner departure/transfer audit и policy preserving personal prefs.
- [x] 1.10 Подключить W11 workspace matrix/W12 authorised cohort и read-only kiosk.
- [x] 1.11 Проверить editor-without-publish, viewer filters isolation, foreignworkspace share, hidden count leakage, revoke и stale cache.
- [x] 1.12 Провести два live user sessions с разными device grants, publication conflict и keyboard/mobile sharing UI.
- [x] 1.13 Выполнить focused authorization/domain tests и обновить current sharing verification.

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

Sharing acceptance: separate live actors verify per-author drafts, edit without publish, stale-base 409, safe export/import, viewer-specific panel denominators and immediate revocation with no cached values. Additional acceptance rejects foreign audiences and checks preview and ownership transfer audit. Two signed browser sessions showed 2 versus 1 authorized devices. Fresh browser load verifies editor without publish/copy/export and authenticated read-only kiosk; mobile 390 px and keyboard dialog closure exercised. Existing screen/layout/publication tests preserve presentation baseline.
