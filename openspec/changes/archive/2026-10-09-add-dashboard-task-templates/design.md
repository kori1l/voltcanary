# Бизнес-шаблоны и привязка к реальным данным: design

## Context And Goal

Гибкий blank editor требует знаний метрик; шаблоны должны решать задачи смены/техника/владельца, не подставлять чужие ID или выдуманные temperature/energy/cycles.

Coverage: DB-03, DB-04, DB-05, DB-16, DB-17, DB-18, DB-22, W01, W02, W03, W04, W05, W06, W07, W08, W09, W10, W12, W13. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../../README.md).

## Templates
Смена: W01/W07 KPI и incidents +W08 next7d +W03 telemetry6h +W10quality. Мои устройства:3–6W02 +W07 +W03 +W10,24h. Холодильное оборудование:FRIDGE24h, W02temperature only available,W03 separate compatible power/temp charts,W07/W09/W08. Техник:W09high/unknown,W07,W08next7d,W10,W13; assignment filter not offered absent API. Energy:W05/W04peak/W06/W03/W10,7d vs prior7d; unavailable until verified aggregate methods.
## Binding workflow
GET /dashboard-templates returns version/task/persona, required capabilities, default widgets/layout/time and symbolic slots. User maps permitted location/devices, sees resolved count/time and missing capabilities before preview. Create snapshot copies chosen template version; later template update does not alter published user dashboards.
## UX validation
No mandatory business questionnaire; task chosen or skipped. Segments café/store/laundry/property/workshop/IT/service/home are hypotheses, labels suggest and never grant access. No power→temperature/uptime/order inference. Temperature missing panel can be explicitly removed or left annotated; generated/demo evidence visibly marked. Pilot manager/technician/owner goals creation≤3m/configure≤90s require measured observations.

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

