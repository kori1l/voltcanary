# Серверные личные дашборды, версии и предпочтения: design

## Context And Goal

Текущий Dashboard фиксированный; server definitions/drafts/revisions/preferences отсутствуют. Browser-only JSON не даёт изоляции, восстановления между сессиями и безопасного publish.

Coverage: DB-08, DB-09, DB-10, DB-12, DB-22, DB-33, DB-38, DB-39, DB-40, DB-43. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Proposed ownership
Новый backend/services/dashboard-service на 8089, база voltcanary_dashboard, Flyway. Зафиксировать решение ADR перед wiring. Domain use cases: CreateDashboard, SaveDraft, PublishRevision, ArchiveDashboard, CopyDashboard, PersonalPreferences; controllers/repositories/HTTP ports — adapters. Dashboard не владеет raw business evidence.
## Tables and bounds
dashboards(id,owner_user_id,account_id,name,description,status,published_revision,schema_version,created_at,updated_at); dashboard_revisions immutable JSON typed schema; dashboard_drafts author+baseRevision+etag; personal_preferences user/account; idempotency records and lifecycle audit without credentials. 10 active dashboards/drafts per user, 24 widgets, name1–80/description≤500; shared grants later nullable workspace context. Unknown widget payload preserved inert; reject unsafe URLs/code and secrets.
## API and concurrency
GET/POST /dashboards, GET /dashboards/{id}, GET/PUT /draft, POST /publish,/copy,/archive,/restore, GET /revisions, GET/PUT /dashboard-preferences/me. Cursor list20/max100. If-Match stale→409, missing required precondition→428, invalid fields→422. Idempotency-Key bound actor/account/route/body hash for24h: same retry same result; different body409; recheck authorization before replay. Atomic publish increments revision without overwriting history.
## Security and migration
Resolve current user/account server-side, ignore supplied ownership. Only active owner reads private dashboard/revisions/draft/query/export metadata; ADMIN/SUPPORT no blanket bypass. Cross-user IDs same404 as missing. Existing /dashboard fallback unchanged until editor/catalog integration. No backfill of personal definitions from arbitrary browser storage. New service seed/deploy gates, old APIs stable.

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
