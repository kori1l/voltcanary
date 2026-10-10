# Tasks: Командные дашборды, публикация и совместный доступ

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-team-dashboard-publication`, затем соответствующие
project surface skills. Покрытие: DB-02, DB-05, DB-10, DB-11, DB-22, DB-31, DB-34, DB-35, DB-36, DB-37, W11, W12.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Определить dashboard grant matrix и view/copy/history/export policies отдельно от resource grants.
- [ ] 1.2 Добавить workspace dashboard ownership/grant/audit migrations с private v1 compatibility.
- [ ] 1.3 Реализовать grants read/write и recipient/team active membership checks.
- [ ] 1.4 Реализовать safe sharing-preview без назначения resource access и hidden names/counts.
- [ ] 1.5 Добавить Team listing/search metadata только разрешённых objects и minimal audience UI.
- [ ] 1.6 Добавить shared author drafts, publish-only grant и409 conflict recovery.
- [ ] 1.7 Применить per-viewer scope intersection/provenance и invalidate cache on grants/resource revoke.
- [ ] 1.8 Добавить safe copy/export/import placeholder rebind без ACL/telemetry/secrets.
- [ ] 1.9 Добавить owner departure/transfer audit и policy preserving personal prefs.
- [ ] 1.10 Подключить W11 workspace matrix/W12 authorised cohort и read-only kiosk.
- [ ] 1.11 Проверить editor-without-publish, viewer filters isolation, foreignworkspace share, hidden count leakage, revoke и stale cache.
- [ ] 1.12 Провести два live user sessions с разными device grants, publication conflict и keyboard/mobile sharing UI.
- [ ] 1.13 Выполнить focused authorization/domain tests и обновить current sharing verification.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Проверить Dashboards → /dashboards, sidebar links only, catalog item actions/menu visibility, numeric grid profiles/flexible panel sizes, named screens, placement, right drawer, скрываемую навигацию, minimal defaults, optional annotations и обязательные compact exceptional states; выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
