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

## Dashboard navigation and grid baseline

Follow [dashboard navigation](../../specs/dashboard-navigation/spec.md),
[clean sidebar and catalog management](../../specs/dashboard-sidebar-management/spec.md)
and [numeric grids and flexible panels](../../specs/dashboard-grid-restoration/spec.md).
The Dashboards parent opens /dashboards; child links open concrete dashboards.
The sidebar contains links only. Search, creation, item actions and menu visibility
belong in the catalog; runtime data/time/refresh controls stay in structured right
settings. Do not add another My dashboards destination, sidebar action icons or a
permanent toolbar above the canvas. Menu hiding is a personal local preference,
not archive, sharing or a resource grant. New panels retain numeric grid profiles,
manifest minima down to 1x1 where supported, and charts use their available height.

## Dashboard presentation baseline

Use the established [working screens](../../specs/dashboard-working-screens/spec.md),
[compact workspace](../../specs/compact-dashboard-workspace/spec.md) and
[minimal presentation](../../specs/minimal-dashboard-presentation/spec.md) contracts.
New panels belong to the user-selected named screen and support explicit
above/below/left/right placement within responsive bounds. Configuration uses
the right drawer; navigation remains hideable. Healthy panels default to the
visualization and units, with optional titles, legends, calculations, comparisons
and inline details. Authorized scope, methods, denominators and provenance remain
available in panel information. Partial, stale, estimate, unavailable and denied
states stay visible as compact indicators. Screens and layouts survive copy and
publication; queries read only active-screen facts and publication validates all
screens. Task/persona screen names never grant resource permissions.

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
