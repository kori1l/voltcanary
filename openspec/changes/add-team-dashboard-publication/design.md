# Командные дашборды, публикация и совместный доступ: design

## Context And Goal

Общий dashboard полезен смене и филиалам, но доступ к представлению не должен выдавать доступ к устройствам; личные filters не должны менять командную published version.

Coverage: DB-02, DB-05, DB-10, DB-11, DB-22, DB-31, DB-34, DB-35, DB-36, DB-37, W11, W12. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Grants and drafts
GET/PUT /dashboards/{id}/grants требует manage-sharing и active workspace membership. Owner grants all dashboard capabilities; editor не имеет publish автоматически. Draft perauthor/baseRevision; concurrent publication409 handling inherited. Transfer ownership validated workspace manager, departed owner no access.
## Sharing preview
Planned POST /dashboards/{id}/sharing-preview returns whether selected active recipients/teams can use configured widgets, without listing their hidden private devices. Preview does not grant resources. View query intersects current recipient access; footer says shown your accessible devices and only authorized denominator. Avoid leaking total hidden fleet through difference counts, titles, aggregation or cache keys.
## Personal versus published context
Shared defaults only Edit+Publish; viewer runtime context/favorites/default personal. Copy requires view and explicit copy capability policy, creates private independent draft without ACL or unsupported recipient bindings. Definition export safe schema/placeholder bindings, import rebind, no telemetry/secrets/audience grants. Anonymous external embed prohibited.
## Teams and live screen
New Team tab with metadata authorized only. W11 location matrix scoped current workspace subset. W12 model traces require ML read grants; no live inference. Authenticated read-only kiosk shows scope/time/lastrefresh/connectivity and never exposes mutating commands.

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
