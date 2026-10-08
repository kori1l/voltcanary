# Типизированные запросы виджетов и полные агрегаты: design

## Context And Goal

Виджетам нужен общий resolved scope/time, полнота парка и typed business intent; композиция frontend из bounded lists даёт ложные totals и uncontrolled request fanout.

Coverage: DB-16, DB-17, DB-18, DB-19, DB-20, DB-21, DB-23, DB-24, DB-26, DB-27, DB-28, DB-29, DB-30, DB-31, DB-32, DB-39, DB-40, DB-41, DB-42, DB-43, W01, W02, W03, W04, W07, W08, W09, W10, W11, W12, W13. Implemented query runtime checkpoint 2026-10-09; viewer/editor remains a separate change.
Матрица владения и порядок поставки: [program index](../README.md).

## Query context and authorization
Запрос выбирает разрешённый published revision или собственный draftRef плюс visible widget IDs/runtimeContext. Type/metric/reducer/time/scope allowlists из server manifests; запрет SQL, code, arbitrary HTTP URLs. Fixed IDs/dynamic location subtree/type/location.tags intersect current authorised devices; device.tags capability only when contract exists. Fixed selection не заменяется новым device. Request uses one asOf and contextHash; downstream ownership checks не ослабляются.
## Time and math
Inherited/fixed/relative widget timeMode виден. Half-open UTC interval[from,to), calendar Europe/Belgrade and DST resolved before all reads; compare previous equal-duration/manual, zero denominator undefined. Snapshot active incidents distinct from event-created-in-period. Freshness max(3×cadence,60s), unknown cadence default5m labeled; sparse/manual/import policy explicit. Risk score≠probability/confidence≠accuracy. Full requested/evaluated authorised count, coverage definition and quality provenance; no bounded list length total. Server reducers read full qualified evidence, p95 sample weighting declared, no display-curve reducer.
## Catalog and state
W01–04/W07–13 base manifests. W11 single-account location matrix initially, workspace scope later; W05/06 registered only after energy change. W14/15 unsupported dependency explanation. Each server result has ready/no_data/unavailable/no_access/unsupported/invalid, data/time/provenance and typed error; client resource hooks own loading/stale transitions. Failed panel independent; removed permission purges cached values/names. Raw maintenance energy unaffected while derived analytics exclusions preserve contracts.
## Fanout and budgets
24 widgets,8series,50table rows/page,20ranking,500scope devices/50 individually charted devices; overflow422, not silent truncation. One batch deduplicates query ports with concurrency6 and panel timeout5s. Cache includes viewer authorization fingerprint/revision/context/units/reducer; current lifecycle and grants rechecked before cache serve. Live default1m,min30s,off historical,jitter/backoff/hidden pause handled viewer. Performance acceptance12widgets/100devices/24h: meaningful≤2s, warm p95 all successful≤5s; budgets measured, not current claims.

## Failure Modes And Compatibility

Implementation обязана сохранить canonical IDs, tenant/account lifecycle и server
resource checks. Unauthorized данные не попадают в URL, cache, local drafts,
telemetry logs или screenshots при верификации. Existing routes/API не меняются
несовместимо без отдельно указанной migration; new fields/endpoints additive.
Late responses cannot replace the selected context. UI completion is not evidence
of runtime delivery, inference, production readiness or measured performance.

## Verification And Rollout

Scenarios в specs/**/spec.md — обязательная приёмка, tasks.md остаётся unchecked
до кода и доказательств. Начать с focused tests, затем browser/integration на
реальных доступных records для затронутых flows. Документировать exercised scope,
normal/loading/empty/partial/error/stale/no-access и ограничения; generated evidence
оставлять в ignored artifacts. Встраивать feature постепенно, сохраняя working
fallback. Не проводить load claims на основании build или одного smoke.
