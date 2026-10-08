# Готовность ML по выбранной цепочке данных: design

## Context And Goal

Общий pipeline сейчас считает любой matching snapshot/run/model признаком готовности, включая QUEUED/RUNNING; эти объекты могут относиться к другим dataset, scope и horizon.

Coverage: R02. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Readiness selector
Focused pure selector получает selected context, typed resource states и canonical server lineage. DatasetVersion ID, feature snapshot lineage, family/horizon и device/account scope сравниваются до ready. Training progress — queued/running, completed training ready только после SUCCEEDED и ссылок на artifact/model. Approval и serving readiness — разные шаги.
## Missing metadata
Не выводить совместимость из названия или наличия массивов. Если DTO не предоставляет lineage/horizon, добавить только read metadata к analytics contract с migration/backfill для известных записей; legacy unknown остаётся needs-review. Server approval/deployment guards остаются authoritative.

## Implemented compatibility matrix

| Evidence | Required canonical match | Ready evidence |
| --- | --- | --- |
| Dataset | Selected datasetVersionId and accountId; selected device in detail lineage when device scoped | Server READY |
| Labels | Selected datasetVersionId, accountId, horizon and optional deviceId | Selected positive failure labels; anomaly does not require labels |
| Snapshot | Dataset/account/family; failure horizon; known ACTIVE lifecycle | Positive rows and recorded artifact |
| Run | Selected snapshot/dataset/account/family and compatible explicit horizon parameters | SUCCEEDED with completion time/artifact; QUEUED/RUNNING are progress |
| Model | Selected run/snapshot/dataset/account/family and compatible device type | Server APPROVED/ACTIVE with approval timestamp/artifact, completed matching run |
| Policy | Selected approved model, owner and account/device/type scope | Non-DISABLED configuration; SHADOW/CANARY/ACTIVE remain explicit |
| Inference | Selected device/model/family/failure horizon | Actual MODEL/TRAINED_MODEL record and its timestamp, independent of configured policy |

DTO inventory: snapshots already expose account/dataset/family/horizon and
lifecycle; runs expose account/dataset/snapshot/family; models persisted
trainingRunId but did not return it, and dataset summary omitted its persisted
accountId. Both are additive read fields mapped from existing columns. No schema
migration or inferred backfill is needed. Null training lineage remains unknown.
Model device/metric/evaluation JSON fields are decoded at the frontend API
boundary. Failure horizon follows the canonical snapshot link; explicit run
parameters must agree. Unknown device lineage/legacy family/lifecycle/approval
metadata never establish readiness.

Focused selector: `pipelineContext.ts`; selection panel: `PipelineContextSelection`.
Dataset/snapshot defaults are visibly selected; run/model/policy remain explicit.
Dataset/label/training navigation retains this context. Label resources are
dataset keyed and reject obsolete responses; unknown inventories preserve the
shared resource feedback from the completed prerequisite change.
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
