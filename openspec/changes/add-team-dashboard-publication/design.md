# Командные дашборды, публикация и совместный доступ: design

## Context And Goal

Общий dashboard полезен смене и филиалам, но доступ к представлению не должен выдавать доступ к устройствам; личные filters не должны менять командную published version.

Coverage: DB-02, DB-05, DB-10, DB-11, DB-22, DB-31, DB-34, DB-35, DB-36, DB-37, W11, W12. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Grants and drafts
GET/PUT /dashboards/{id}/grants требует manage-sharing и active workspace membership. Owner grants all dashboard capabilities; editor не имеет publish автоматически. Draft perauthor/baseRevision; concurrent publication409 handling inherited. Transfer ownership validated workspace manager, departed owner no access.
## Sharing preview
Planned POST /dashboards/{id}/sharing-preview returns whether selected active recipients/teams can use configured widgets, without listing their hidden private devices. Preview does not grant resources. View query intersects current recipient access; panel information and filters explain the accessible subset and its authorized denominator without a permanent footer. Partial/unavailable states remain visible as compact indicators. Avoid leaking total hidden fleet through difference counts, titles, aggregation or cache keys.
## Personal versus published context
Shared defaults only Edit+Publish; viewer runtime context/favorites/default personal. Copy requires view and explicit copy capability policy, creates private independent draft without ACL or unsupported recipient bindings. Definition export safe schema/placeholder bindings, import rebind, no telemetry/secrets/audience grants. Anonymous external embed prohibited.
## Teams and live screen
Team is a category in the existing /dashboards catalog, not a separate My dashboards destination or a mandatory screen within each dashboard. Shared definitions preserve all named task screens, their independent widget sets and responsive layouts; screen selection reads only the active screen and publication validates all screens. W11 location matrix scoped current workspace subset. W12 model traces require ML read grants; no live inference. Authenticated read-only kiosk keeps scope/time available on demand and stale timing/connectivity failures visible, and never exposes mutating commands.

## Dashboard navigation and grid baseline

Follow [dashboard navigation](../../specs/dashboard-navigation/spec.md),
[clean sidebar and catalog management](../../specs/dashboard-sidebar-management/spec.md)
and [numeric grids and flexible panels](../../specs/dashboard-grid-restoration/spec.md).
The Dashboards parent opens /dashboards; child links open concrete dashboards.
The sidebar contains links only. Search, creation, item actions and menu visibility
belong in the catalog; runtime data/time/refresh controls stay in structured right
settings. Do not add another My dashboards destination, sidebar action icons or a
permanent toolbar above the canvas. Menu hiding is a personal local preference,
not archive, sharing or a resource grant. New panels retain numeric grid profiles,
manifest minima down to 1x1 where supported, and charts use their available height.

## Dashboard presentation baseline

Use the established [working screens](../../specs/dashboard-working-screens/spec.md),
[compact workspace](../../specs/compact-dashboard-workspace/spec.md) and
[minimal presentation](../../specs/minimal-dashboard-presentation/spec.md) contracts.
New panels belong to the user-selected named screen and support explicit
above/below/left/right placement within responsive bounds. Configuration uses
the right drawer; navigation remains hideable. Healthy panels default to the
visualization and units, with optional titles, legends, calculations, comparisons
and inline details. Authorized scope, methods, denominators and provenance remain
available in panel information. Partial, stale, estimate, unavailable and denied
states stay visible as compact indicators. Screens and layouts survive copy and
publication; queries read only active-screen facts and publication validates all
screens. Task/persona screen names never grant resource permissions.

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
