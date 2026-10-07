# Готовность ML по выбранной цепочке данных: design

## Context And Goal

Общий pipeline сейчас считает любой matching snapshot/run/model признаком готовности, включая QUEUED/RUNNING; эти объекты могут относиться к другим dataset, scope и horizon.

Coverage: R02. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Readiness selector
Focused pure selector получает selected context, typed resource states и canonical server lineage. DatasetVersion ID, feature snapshot lineage, family/horizon и device/account scope сравниваются до ready. Training progress — queued/running, completed training ready только после SUCCEEDED и ссылок на artifact/model. Approval и serving readiness — разные шаги.
## Missing metadata
Не выводить совместимость из названия или наличия массивов. Если DTO не предоставляет lineage/horizon, добавить только read metadata к analytics contract с migration/backfill для известных записей; legacy unknown остаётся needs-review. Server approval/deployment guards остаются authoritative.
## Compatibility
Аномалия не требует failure labels/horizon. Positive labels учитываются в выбранном dataset, не глобально. Policy проверяет model ID, purpose/family, scope и disabled/shadow/canary/active; configured policy не доказывает успешный runtime inference.

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
