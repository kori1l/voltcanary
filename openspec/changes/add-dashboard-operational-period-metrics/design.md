# Рабочие расписания, подтверждённые циклы и простой: design

## Context And Goal

Прачечной/мастерской нужны энергия на цикл и downtime, но spikes мощности не являются заказами, а пропуск telemetry не доказывает остановку процесса. Рабочие/нерабочие часы пока не имеют domain contract.

Coverage: W15, DB-17, DB-20, DB-21, DB-26, DB-27. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Domain boundaries
device-service owns OperatingSchedule and OperationalEvent facts scoped account/location/device; events distinct from telemetry metric whitelist, no arbitrary extra ingestion metrics. Proposed /operating-schedules and /operational-events APIs require authenticated source/user grants, canonical eventId/sourceId/deviceId, start/end/kind and reason/evidence. Existing sensor imports unchanged. Machine-specific detector later separate spec; this change supports explicit confirmed events/imports, not unvalidated peak classifier.
## Time and facts
Schedule effective revisions timezone/week slots/holidays, halfopen UTC resolution and DST gaps/overlaps declared. Cycle facts completed/aborted/incomplete and downtime observed/planned/corrected with explicit cause optional; duplicate source event dedup, outoforder correction reason/audit, no invented closure. Overlapping downtime intervals union once; maintenance exclusion policy separate and displayed, gaps→unknown not zero downtime.
## Derived widgets
Energy-per-cycle uses only complete confirmed cycles with energy boundary coverage; partial/aborted counts separate. Uptime denominator confirmed observation window/schedule, no runtimeHours delta as job count, missing telemetry not confirmed downtime. Schedule-normalized comparisons match operating windows/duration and show denominators. Business orders, throughput, OEE and financial savings excluded without separate contracts.

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
