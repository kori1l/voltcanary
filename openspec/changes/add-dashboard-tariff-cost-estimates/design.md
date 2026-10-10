# Версионированные тарифы и оценка стоимости энергии: design

## Context And Goal

Владельцу нужен расход в деньгах, но current tariff/currency contracts отсутствуют. Умножение неполной энергии на arbitrary price создаёт ложный bill и экономию.

Coverage: W14, DB-17, DB-21, DB-26, DB-34. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Domain and schema
EnergyTariff domain в device-service как account/workspace commercial policy, отдельные use cases вместо catch-all SettingsService; денежные calculations authorised read port для dashboard. Fields tariffId/version/currency ISO4217, timezone, effectiveFrom/to, ratePerKWh decimal, optional timeBands; no floating currency sums. First release flat rate; time-band UI only with validated overlap/coverage DST contract.
## Estimate
Cost=sum(qualified kWh segment×active tariff rate), segments split tariff revisions and local time bands. Request attaches energy method/coverage/tariff version, explicit taxesIncluded and fixedFeesExcluded policy. Missing period tariff→partial/unsupported, not zero cost; currencies not auto-converted. Tariff write owner/private or workspace tariff.write, read tariff.read; chart/edit permissions do not grant cost visibility.
## Compatibility
Target endpoints /energy-tariffs list/create/read/revise/archive. Revision changes history prospectively; backdated correction audited and explicit recalculation read behavior, not silent past edits. W14 returns estimated cost only; no billing ledger, payment or demonstrated savings. Comparisons samecurrency/equalperiod/comparablecoverage only; all configuration remains draft/reviewed.

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
