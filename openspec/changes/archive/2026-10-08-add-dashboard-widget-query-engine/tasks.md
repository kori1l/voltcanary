# Tasks: Типизированные запросы виджетов и полные агрегаты

Implementation и verification завершены 2026-10-09; финальная сверка gates ниже.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-widget-query-engine`, затем соответствующие
project surface skills. Покрытие: DB-16, DB-17, DB-18, DB-19, DB-20, DB-21, DB-23, DB-24, DB-26, DB-27, DB-28, DB-29, DB-30, DB-31, DB-32, DB-39, DB-40, DB-41, DB-42, DB-43, W01, W02, W03, W04, W07, W08, W09, W10, W11, W12, W13.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Определить typed query/result/manifest schemas и validation API; запрет unsupported free-form query/code.
- [x] 1.2 Реализовать fixed/dynamic scope resolver, metadata capabilities, subtree/tag namespaces и active owner enforcement.
- [x] 1.3 Определить UTC half-open/calendar/DST/asOf/contextHash и compare semantics без percent при base0.
- [x] 1.4 Добавить full-scope counts/read aggregates у domain владельцев и batched read ports без frontend page-length totals.
- [x] 1.5 Добавить W01–04/W07–13 manifests/renderable result types; W11 ограничить single account до membership.
- [x] 1.6 Сохранить per-panel states/quality/receivedAt/measuredAt/excluded interval provenance и no-access purge.
- [x] 1.7 Реализовать freshness policies sparse/manual/import, canonical units и server/full-data reducers.
- [x] 1.8 Реализовать query dedup/concurrency6/timeout5s и authorization-fingerprinted cache с renewed checks.
- [x] 1.9 Добавить budgets24widgets/8series/500scope/50detail/50rows и typed limit errors.
- [x] 1.10 Покрыть250devices/DST/zero comparator/scope change/partial outage/foreign UUID/cache isolation/no mutation reads.
- [x] 1.11 Выполнить service boundary tests dashboard/device/telemetry/analytics/alert по затронутым contracts.
- [x] 1.12 Запустить measured12-widget100-device performance profile cold/warm/failed dependency и приложить sanitized evidence; не принимать target latency без замера.
- [x] 1.13 Обновить additive API docs/ownership/read state contracts и OpenSpec acceptance.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Выполнить focused Maven/migration/authorization integration проверки затронутых сервисов; не принимать readiness по одному health response.
- [x] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.

## Acceptance evidence — 2026-10-09

- Server catalog, saved revision/current author draft, visible widget IDs and
  validation-only endpoints verified through MockMvc with PostgreSQL. Unknown
  versions remain inert; foreign dashboards404, stale references409, unsafe
  request fields and resource budgets422. Active query actor resolution uses
  read-only UNBOUND scope, never account bootstrap.
- Device PostgreSQL acceptance covers250 devices, subtree/location.tags,
  denied IDs, configured cadence without secret fields, signed owner vs spoofed
  headers, inactive account, and full maintenance count separate from pages.
- Telemetry Timescale acceptance covers250 devices/500 qualified samples,
  zeros, late maximum, exact half-open endpoint, mean/p95 and quality namespaces.
  Analytics reads stored qualified risk/prediction metadata without ML calls/jobs;
  incident counts250 are independent of20 returned rows and notification attempts.
  Owner internal endpoints require both internal token and viewer bearer.
- Dashboard tests29 passed: DST25h/23h calendar days, fixed override/runtime
  change, full250 scope, cache renewed access/purge, foreign actor isolation,
  single flight, concurrent maximum6, five-second timeout with independent panel,
  validation/no write calls, supported/non-ready confidence and canonical units.
  Root `backend/mvn test` passed every module; final dashboard-only refinements
  received the full focused service suite. Shared common-web DTO required the root gate.
- Frontend typecheck/build pass;59 files/176 tests pass with two workers.
  Typed API/read hook tests cover aborted/late old context and actor purge.
  Existing ML test fixture now supplies real dataset detail/labels; its multi-action
  lifecycle test has15s harness timeout. UI eventual-state waits use3s to tolerate
  concurrent local/CI work; these are correctness tests, not latency claims.
- `scripts/dashboard-query-profile.ps1 -FailureProfile`: real JWT/local Compose,
  isolated100 devices,14,400 samples,12 widgets/24h. Final cold-cache forced
  samples404.52/279.79/281.32ms; ten warm samples p95 127.53ms; actual stopped
  telemetry dependency4092.53ms. Independent incidents/maintenance/risk/prediction
  return known no-data and note remains ready; failed telemetry panels contain no
  values. Telemetry restored in finally. Full-count fixture assertions and published
  capability validation pass. Earlier first-facts batch1378.91ms also meets2s.
- Sanitized ignored evidence: `artifacts/dashboard-query-root-maven.log`,
  `dashboard-owner-boundary-tests.log`, `dashboard-query-completed-tests.log`,
  `dashboard-query-frontend-final-verification.log`, `dashboard-query-final-profile.json`.
  Passwords/bearers were held in process memory, not persisted in evidence.
- No editor/viewer routes, templates, team grants, energy/cost/cycle calculations,
  distributed snapshot or production capacity claim. The profile is a single local
  cold-cache/warm-query test, not fresh-JVM/ingestion/soak/scaling acceptance.
  Existing structural warnings remain outside this change; no new oversized service
  or constructor was introduced. Existing AGENTS validation routing remains applicable.
- Hard dependencies private dashboard domain, evidence-state foundation and
  readable telemetry history are already archived and delivered. Accessibility
  native zoom/speech gates are separate and remain pending. User scope ends here;
  the visual editor and all later changes are unstarted.

Final gates: strict OpenSpec36/36 and structure checks pass; existing warnings only.
