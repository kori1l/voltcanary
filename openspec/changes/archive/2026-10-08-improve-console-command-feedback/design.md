# Результат команд, локальный pending и понятные ошибки: design

## Context And Goal

Успешное создание канала с failed list reload выглядит неуспешным сохранением; повторные row clicks могут запускать одинаковую команду; raw fetch сообщения не объясняют следующее действие.

Coverage: R14, R16, R27. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Command lifecycle
Focused command helpers имеют idle/reviewing/submitting/committed/unknown-result/failed state. После successful response ID/result фиксируется до reload; failed reload не делает committed failed. Transport failure до canonical response — outcome unknown; запрет на слепой retry non-idempotent create без reconcile.
## Concurrency and error mapping
Pending key entityId+action предотвращает double click; независимые строки не блокируются. 401 session handles belong context/session change, 403 access, 422/validation field errors, network transient and 503 dependency distinguish in typed error without secret/provider URLs. Diagnostics under disclosure.
## Copy and recovery
Copy ID reports success/failure via status region. Recovery UI explains actual configured delivery availability and manual token as fallback. Не внедрять new email provider в этом UI change.

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
