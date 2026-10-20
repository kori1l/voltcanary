# Tasks: Workspace, участники и отдельные права на ресурсы

Задачи завершены после implementation и verification; evidence приведён ниже.
Перед кодом: `npm.cmd run openspec:apply -- add-workspace-resource-membership`, затем соответствующие
project surface skills. Покрытие: DB-33, DB-34, DB-35, DB-36, DB-37.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Зафиксировать ADR identity membership/device resource-grant ownership и personal account compatibility.
- [x] 1.2 Спроектировать workspace/team/member/resource-account association migrations и consent/active lifecycle.
- [x] 1.3 Определить permission matrix по resource action/scope; запрет ADMIN/SUPPORT blanket business access.
- [x] 1.4 Реализовать workspace/member/team APIs с existing-user checks и review без unsolicited email delivery.
- [x] 1.5 Добавить owner-controlled account association и explicit device/location scope grants.
- [x] 1.6 Реализовать common authorization port/request context с spoofed-header stripping и service-auth internal lookups.
- [x] 1.7 Интегрировать grants в device/telemetry/analytics/alerts/maintenance reads и action-specific mutations без weakening existing owners.
- [x] 1.8 Добавить grants version/fingerprint и revoke/disable lifecycle propagation с current checks перед cache.
- [x] 1.9 Добавить минимальный reviewed membership/resources UI и audit/owner transfer workflow.
- [x] 1.10 Покрыть crossworkspace IDs, team member subset, account association spoof, stale JWT/cache после revoke и lifecycle disable.
- [x] 1.11 Проверить old personal ownership APIs/regression и domain action permissions без dashboard role inheritance.
- [x] 1.12 Выполнить affected backend/common-security Maven tests, tenant isolation integration smoke и docs/API/event consistency.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
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

Workspace acceptance: live gateway verifies reviewed association, strict team subset, foreign-workspace/header spoof denial, independent action rights, revocation and stale JWT. Additional acceptance verifies owner transfer, automatic previous-owner departure, audit, withdrawn consent after departure and preserved personal device reads. Current unit tests cover account/actor lifecycle disable. ML write grants remain reserved and do not broaden personal ML write endpoints.
