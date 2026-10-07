# Доступность, мобильные рабочие экраны и загрузка по маршрутам: design

## Context And Goal

ML selected tab обозначается только CSS, Agenda содержит nested actions в button-role контейнере, required marker переносится строкой; общий bundle загружает ML/Admin для login.

Coverage: R11, R21, R22, R24, R25, R26. Proposed design, not current runtime.
Матрица владения и порядок поставки: [program index](../README.md).

## Controls and page structure
Tabs follow tablist/tab/tabpanel, aria-selected/controls, roving tabindex and arrows; intentional manual activation for heavy requests. Agenda row uses separate Open command, never nested button role. Busy/errors/status are announced once and form hints are linked. Required marker stays inline label.
## Navigation and mobile
Группы Operations/ML workspace/Personal settings/Admin — labels, не permissions. Dashboard USER entry belongs dashboard catalog change. Mobile inventory preserves device name/freshness/risk/action; wide detail tables keep local scroll. ML all eight sections discoverable via accessible selector if prototype validates improvement.
## Delivery
React.lazy route chunks for ML/AI/Admin/details; shared shell/auth stays minimal. Stable dimensions for skeleton/error and retry on chunk fetch failure. Preserve logo/neutral console, remove nth-child status meaning and undefined token; limit cleanup to touched styles.

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
