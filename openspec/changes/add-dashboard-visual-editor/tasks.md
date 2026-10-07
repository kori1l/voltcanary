# Tasks: Визуальный редактор и персональный стартовый экран

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-visual-editor`, затем соответствующие
project surface skills. Покрытие: DB-01, DB-02, DB-03, DB-05, DB-06, DB-07, DB-08, DB-09, DB-10, DB-11, DB-12, DB-13, DB-14, DB-15, DB-22, DB-28, DB-29, DB-30, DB-31, DB-32, DB-43.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Создать typed dashboard client и feature modules; добавить routes/USER entry сохранив /dashboard compatibility.
- [ ] 1.2 Реализовать My/Favorites search/sort metadata и personal default/fallback without loops.
- [ ] 1.3 Реализовать blank/copy creation wizard, validation и real private preview.
- [ ] 1.4 Добавить widget catalog/configure4sections с units/scope/status и safe internal drill-down.
- [ ] 1.5 Реализовать view/edit разделение, sections, layout collision algorithm и manifest sizes.
- [ ] 1.6 Добавить keyboard/pointer menu move/size и20Undo/Redo; separate mobile order без desktop overwrite.
- [ ] 1.7 Реализовать autosave2s/manual, Saving/Saved/Failed, scoped safe recovery и Publish summary.
- [ ] 1.8 Реализовать409 compare/latest/copy/reapply и archive/restore/default fallout.
- [ ] 1.9 Реализовать runtime URL filters/context Apply, defaults persistence и per-panel stale/error/noaccess rendering.
- [ ] 1.10 Реализовать visible-only refresh/backoff/hidden pause/age clocks и authorised kiosk без edit.
- [ ] 1.11 Покрыть double publish, autosave failure, conflict, unknown widget retention, dirty navigation и account isolation.
- [ ] 1.12 Проверить полный USER creation→publish→default→newsession на3demo devices, keyboard/touch/200%text и native widths.
- [ ] 1.13 Измерить interactive editor target≤1s после definition и frontend workflow tests/typecheck/build.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
