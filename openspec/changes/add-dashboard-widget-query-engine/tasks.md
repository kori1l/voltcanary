# Tasks: Типизированные запросы виджетов и полные агрегаты

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-widget-query-engine`, затем соответствующие
project surface skills. Покрытие: DB-16, DB-17, DB-18, DB-19, DB-20, DB-21, DB-23, DB-24, DB-26, DB-27, DB-28, DB-29, DB-30, DB-31, DB-32, DB-39, DB-40, DB-41, DB-42, DB-43, W01, W02, W03, W04, W07, W08, W09, W10, W11, W12, W13.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Определить typed query/result/manifest schemas и validation API; запрет unsupported free-form query/code.
- [ ] 1.2 Реализовать fixed/dynamic scope resolver, metadata capabilities, subtree/tag namespaces и active owner enforcement.
- [ ] 1.3 Определить UTC half-open/calendar/DST/asOf/contextHash и compare semantics без percent при base0.
- [ ] 1.4 Добавить full-scope counts/read aggregates у domain владельцев и batched read ports без frontend page-length totals.
- [ ] 1.5 Добавить W01–04/W07–13 manifests/renderable result types; W11 ограничить single account до membership.
- [ ] 1.6 Сохранить per-panel states/quality/receivedAt/measuredAt/excluded interval provenance и no-access purge.
- [ ] 1.7 Реализовать freshness policies sparse/manual/import, canonical units и server/full-data reducers.
- [ ] 1.8 Реализовать query dedup/concurrency6/timeout5s и authorization-fingerprinted cache с renewed checks.
- [ ] 1.9 Добавить budgets24widgets/8series/500scope/50detail/50rows и typed limit errors.
- [ ] 1.10 Покрыть250devices/DST/zero comparator/scope change/partial outage/foreign UUID/cache isolation/no mutation reads.
- [ ] 1.11 Выполнить service boundary tests dashboard/device/telemetry/analytics/alert по затронутым contracts.
- [ ] 1.12 Запустить measured12-widget100-device performance profile cold/warm/failed dependency и приложить sanitized evidence; не принимать target latency без замера.
- [ ] 1.13 Обновить additive API docs/ownership/read state contracts и OpenSpec acceptance.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить focused Maven/migration/authorization integration проверки затронутых сервисов; не принимать readiness по одному health response.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
