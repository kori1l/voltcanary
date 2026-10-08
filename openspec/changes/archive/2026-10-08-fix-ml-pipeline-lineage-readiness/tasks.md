# Tasks: Готовность ML по выбранной цепочке данных

Implementation и required acceptance завершены 2026-10-08. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- fix-ml-pipeline-lineage-readiness`, затем соответствующие
project surface skills. Покрытие: R02.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Описать compatibility matrix family/horizon/dataset/scope/model/policy и required lineage fields.
- [x] 1.2 Инвентаризировать DTO coverage; спроектировать additive read metadata при реально отсутствующих полях.
- [x] 1.3 Вынести pipelineContext и readiness selectors в focused model helpers.
- [x] 1.4 Связать dataset/snapshot/training/model/policy selection и remediation navigation с одним context.
- [x] 1.5 Отобразить QUEUED/RUNNING/SUCCEEDED и approval/deployment как разные evidence states.
- [x] 1.6 Использовать selected dataset labels и требуемый horizon без лишних anomaly prerequisites.
- [x] 1.7 Сохранить server gates и degraded/legacy unknown; не вычислять approval из произвольных UI thresholds.
- [x] 1.8 Покрыть foreign dataset/scope/family, horizon mismatch, legacy missing lineage, queued/running и disabled policy сценарии.
- [x] 1.9 Если contract расширен, выполнить focused analytics Maven tests и обновить docs/current/api/ml-api.md; UI-only изменение не требует training smoke.
- [x] 1.10 Проверить оба mode и recovery после unavailable resource в браузере.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [x] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.

## Acceptance evidence

Frontend: typecheck, 35 files / 107 tests, production build pass. Analytics Maven: 136 service tests plus 2 common-events tests pass, including PostgreSQL integration and read-metadata JSON serialization. OpenSpec strict: 41 items pass; structure passes with existing warnings. Browser actual-component fixtures at 390/768/1440: both families, RUNNING progress, approved model/disabled policy, foreign dataset and 14-versus-7-day mismatch, compatible selected failure lineage, unavailable-resource local recovery preserving dataset/model selection. Existing dataset owner and model trainingRunId columns are exposed as additive read metadata; no migration or synthetic backfill. Local analytics restarted and health UP. No training/inference/artifact format or approval-gate behavior changed; training production smoke is not required by task 1.9. This is UI/contract acceptance, not new training/serving/capacity evidence.
