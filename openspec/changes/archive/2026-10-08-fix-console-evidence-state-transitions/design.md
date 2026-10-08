# Достоверные состояния ресурсов и свежесть данных: design

## Context And Goal

При отказе API отдельные секции остаются Loading, pipeline принимает error за отсутствие prerequisites, сохранённый risk не помечается в строке, а delivery history сообщает отсутствие попыток после failed fetch.

Coverage: R01, R03, R04, R05, R09, R10, R15. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Resource state and scope
Каждый ресурс хранит scopeKey, requestGeneration, data, status, measuredAt, receivedAt, error kind и refreshing. Scope A не становится результатом B; permission loss удаляет retained values, обычный transport error оставляет данные только того же scope с stale badge. Waiting-dependency завершается unavailable после окончательной ошибки зависимости, а не остаётся loading.
## Owners
useSectionResource/SectionFeedback владеют shared semantics; inventory signals и alert attempts — entity maps в focused hooks. ML получает resource states, а выбранная lineage остаётся ответственностью fix-ml-pipeline-lineage-readiness. Уже исправленный Current device load feedback сохраняется; его не переписывать без воспроизводимого regression.
## Retry and clock
Retry datasets вызывает только dataset loader. Повторные transport errors одной зависимости группируются, при этом section status доступен. Clock tick меняет labels без fetch; auto-refresh запускает только reads, с jitter/backoff, отменой scope и pause при document.hidden. UTC измерения отделены от UI time.

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
