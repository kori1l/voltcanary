# Workspace, участники и отдельные права на ресурсы: design

## Context And Goal

Текущие accounts принадлежат отдельным пользователям; team dashboards нельзя безопасно построить на переименовании USER/ADMIN или копировании owner JWT.

Coverage: DB-33, DB-34, DB-35, DB-36, DB-37. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Ownership and service boundary
Предлагаем WorkspaceMembership domain в identity-service, registry/resource grants в device-service. Workspace — collaboration boundary, personal device account ownership сохраняется. Device account связывается с workspace только reviewed owner-consent операцией; запись authorised_resource_account связывает workspace/account, не переносит ownership молча. Resource groups are locations/subtrees/types/explicit devices, а не dashboard JSON. Решение persistence/ports фиксируется ADR до реализации.
## Membership and capabilities
Membership ACTIVE/REMOVED; teams within workspace; owner/managers allowed membership administration. Actor capabilities: device.read, telemetry.read, analytics.read, incident.read/ack/resolve, maintenance.read/write, ml.read/write, tariff.read/write (позже). Grants default deny и explicit resource set; grants dashboard-only live separately in team change. Existing USER/SUPPORT/ADMIN identity roles remain; ADMIN no universal tenant read. Cross-workspace service technicians need explicit membership in each workspace, no transitive trust.
## APIs and downstream enforcement
Planned /workspaces, /workspaces/{id}/members,/teams and /workspaces/{id}/resource-grants; owner-controlled account association separate. No email invitation/delivery implementation assumed; add existing user by validated ID after consent/review. Gateway authenticates context, downstream queries resolve intersection against membership/resource grants on each request; internal lookup requires service auth. Existing owner APIs retain behavior when no workspace context; new workspace-scoped reads must not broaden personal routes silently.
## Revocation
Grant version/auth fingerprint invalidates caches; request-time checks authoritative even if invalidation event delayed. Disabled account/member denies old access tokens downstream. Revoke/new member/transfer audit actor/resource/time; authorized manager transfers ownership before owner departure. Do not delete raw telemetry or merge personal data via membership change.

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
