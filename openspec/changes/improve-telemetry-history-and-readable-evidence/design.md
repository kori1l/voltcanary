# Исторические метрики, значимые точки и доступные графики: design

## Context And Goal

Запрос исторических chart keys зависит от последнего reading; uniform downsampling сохраняет один global maximum, но может скрыть trough и другие spikes; SVG не обеспечивает чтение значений без pointer.

Coverage: R19, R20, R23. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Metric discovery
Существующий /telemetry/devices/{id}/metrics предоставляет metadata; уточнить interval semantics и добавить optional from/to только если не доступны. Numeric keys derived from metadata for queried period, not latest. Latest failure не блокирует historic readings. Contract flags imported/quality data and no phantom metrics.
## Display sampling
Pure sampler preserves finite endpoints, per-bucket extrema, threshold crossings and null/long gaps; no synthetic zeros. Hard display budget needs explicit aggregation/envelope representation when crossings exceed budget, not silent drop. KPI reducer always server/full data, not sampled curve.
## Accessible detail
Summary gives count, measured unit, interval, min/max/time and missing evidence. Data table uses existing export cursor pages; keyboard date fields remain, events reachable by named controls. Raw export authorization and max range unchanged.

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
