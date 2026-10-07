# Расход энергии, покрытие и защита от двойного счёта: design

## Context And Goal

energyWh — cumulative meter reading. Сумма readings или разница после reset может создать ложный расход; power integration через gap и parent/submeter sum также искажает KPI.

Coverage: DB-21, DB-23, DB-25, DB-26, DB-27, W05, W06. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Metric ownership and API
Telemetry service владеет measured energy aggregate, device registry — physicalMeterId/counter semantics/replacement/topology metadata. Additive internal batch telemetry energy read port and authorised public query adapter; no frontend display-point sum. Existing readings/latest unchanged.
## Window methods
Window halfopen[from,to), use bracketing boundary samples with declared interpolation and maxGap tolerance. COUNTER_DELTA uses differences only within stable meter segments; negative delta starts explicit reset/rollover/replacement interpretation, never absolute-value consumption. Missing boundary/reset metadata→partial/unverifiable. POWER_INTEGRAL trapezoid (P1+P2)/2×hours/1000; gap>tolerance excluded from covered intervals, result estimate. Both available choose saved authoritative method once, not sum. Sample duplicate/out-of-order normalized by canonical measured timestamp/identity and quality policy.
## Topology and confidence
Before topology contract, aggregate requires explicit independent meters selection and warning; cannot silently allow upstream plus descendant or duplicate physical identity. With topology validated acyclic graph, select non-overlapping meter boundary or children only, never both. Missing devices/windows produce partial totals with authorised evaluated scope and coverage time. Raw energy includes maintenance consumption; derived analytics exclusions separately labeled. Currency/cost excluded here.
## Acceptance
10000→10500Wh gives0.5kWh;reset to100Wh never creates giant negative/positive;2h constant1000W gives2kWh under complete coverage. min/max envelope not integration input, sparse uneven sampling time weighting. W05/W06 and Energy template activated only on verified capability. Tests and bounded 30d profile evidence.

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
