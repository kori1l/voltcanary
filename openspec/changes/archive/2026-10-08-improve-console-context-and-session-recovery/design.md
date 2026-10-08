# Контекст навигации, восстановление сессии и черновики: design

## Context And Goal

Login всегда возвращает в Devices, ML tabs и многие filters живут только в state; переход может потерять draft, assistant получает Settings вместо конкретного ML scope.

Coverage: R12, R13, R17, R28. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Routing contract
Canonical query schemas проверяют tab, entity IDs, ranges/timezone, filters и view. Invalid enum заменяется документированным default; runtime secrets/token никогда не попадают в URL. Login returnTo допускает только same-origin relative application path, исключает //, auth loops и внешние origin.
## Session state
API client координирует один refresh promise для параллельных 401. Failed refresh завершает session once, очищает credentials, показывает session expired и сохраняет безопасный route. Network error refresh не маркируется как failed business mutation; новые tokens не публикуются в сообщения.
## Drafts and assistant
Guard работает для internal navigation/tab и browser unload с сохранением допустимого draft. Persist scope включает user/account/entity/base revision; provider passwords/source tokens хранятся только в памяти. Shared typed context feeds assistant allowlist: dataset, ML tab/scope, maintenance interval/operation, filters, incident. Dashboard context добавляется later editor change.

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
