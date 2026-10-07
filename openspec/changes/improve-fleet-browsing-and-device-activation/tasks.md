# Tasks: Пагинация, экономный поиск и первое измерение

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- improve-fleet-browsing-and-device-activation`, затем соответствующие
project surface skills. Покрытие: R06, R07, R08, R18.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Определить pagination endpoints, stable ordering и server/unknown total behavior для четырёх surface.
- [ ] 1.2 Реализовать Devices/location pages и async entity selector поиск за первым лимитом.
- [ ] 1.3 Добавить Maintenance и Alerts continuation; если нужен cursor endpoint, сохранить старый list contract и ownership tests.
- [ ] 1.4 Заменить status options текущего массива canonical enum и добавить Clear filters.
- [ ] 1.5 Внедрить 300ms search debounce/AbortSignal и раздельные reference loaders.
- [ ] 1.6 Ограничить signal requests concurrency и показывать pending per row, не скрывая error/stale.
- [ ] 1.7 Добавить post-create activation summary и безопасные API/MQTT sender instructions.
- [ ] 1.8 Обновлять first sample state reads без повторного создания/rotation; demo показывать RUNNING/failed фактически.
- [ ] 1.9 Проверить устройства101+, операции201+, пустую страницу, изменение filter/page и superseded search.
- [ ] 1.10 Проверить create success/source continuation/first sample/reload и documented credential impacts.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
