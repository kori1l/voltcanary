# Tasks: Готовность ML по выбранной цепочке данных

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- fix-ml-pipeline-lineage-readiness`, затем соответствующие
project surface skills. Покрытие: R02.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Описать compatibility matrix family/horizon/dataset/scope/model/policy и required lineage fields.
- [ ] 1.2 Инвентаризировать DTO coverage; спроектировать additive read metadata при реально отсутствующих полях.
- [ ] 1.3 Вынести pipelineContext и readiness selectors в focused model helpers.
- [ ] 1.4 Связать dataset/snapshot/training/model/policy selection и remediation navigation с одним context.
- [ ] 1.5 Отобразить QUEUED/RUNNING/SUCCEEDED и approval/deployment как разные evidence states.
- [ ] 1.6 Использовать selected dataset labels и требуемый horizon без лишних anomaly prerequisites.
- [ ] 1.7 Сохранить server gates и degraded/legacy unknown; не вычислять approval из произвольных UI thresholds.
- [ ] 1.8 Покрыть foreign dataset/scope/family, horizon mismatch, legacy missing lineage, queued/running и disabled policy сценарии.
- [ ] 1.9 Если contract расширен, выполнить focused analytics Maven tests и обновить docs/current/api/ml-api.md; UI-only изменение не требует training smoke.
- [ ] 1.10 Проверить оба mode и recovery после unavailable resource в браузере.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
