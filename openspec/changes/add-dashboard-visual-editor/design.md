# Визуальный редактор и персональный стартовый экран: design

## Context And Goal

Пользователь не может создавать и сохранять рабочие представления; обычные USER не видят Dashboard navigation. Редактор без отдельного view/draft режима приведёт к случайным изменениям.

Coverage: DB-01, DB-02, DB-03, DB-05, DB-06, DB-07, DB-08, DB-09, DB-10, DB-11, DB-12, DB-13, DB-14, DB-15, DB-22, DB-28, DB-29, DB-30, DB-31, DB-32, DB-43. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Routes and composition
/dashboards — список, /dashboards/{id} — viewer, /dashboards/{id}/edit — editor; /dashboard — default/fallback compatibility entry. features/dashboards/components/hooks/model и domain API, route shells не превращаются в монолит. My/Favorites names/descriptions searchable; shared tab added team change.
## Edit model
Creation name1–80/description500 → task/bindings → time → real preview → save private draft. Catalog Data/Calculation-time/Presentation/Drill-down; local visual threshold не изменяет alert rules. View has no drag handles. Edit operations Add/Configure/Duplicate/Delete/Move/Resize/sections with20Undo/Redo; Save draft debounced2s, Publish explicit summary. Failure preserves allowed draft metadata only per user/account/id/base revision, never values/tokens. Conflict409 shows latest/copy/reapply, no blind overwrite.
## Responsive and access
Desktop≥1200:12columns;768–1199:6;mobile:1, separate mobile order. Initial cell48px/gap16px token prototype; manifest minsize bounds. Collisions move below without overlap and undo; keyboard/menu Up/Down/Move section/size presets equivalent to drag; screenreader location announcement/focus kept. Preview360/390/768/1440/200%text. Important system stale/connectivity banner not user-hideable.
## Context and lifecycle
Runtime filters/time explicit Apply and URL, never auto-publish. Save personal default explicit; archive/delete confirmation and default fallback read-only overview, restore stable ID. Manual/off/30s/1m/5m refresh, hidden pause, clock-only ages, visible panels only. Kiosk optional authenticated read-only mode with no mutation actions; no public links.

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
