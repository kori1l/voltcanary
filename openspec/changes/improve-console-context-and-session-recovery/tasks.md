# Tasks: Контекст навигации, восстановление сессии и черновики

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- improve-console-context-and-session-recovery`, затем соответствующие
project surface skills. Покрытие: R12, R13, R17, R28.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Определить typed query schemas и безопасный returnTo с тестами external URL/auth loop rejection.
- [ ] 1.2 Добавить single-flight refresh и единое событие session expiration в API layer.
- [ ] 1.3 Обновить RequireAuth/Login для восстановления device/incident/metric/from/to route.
- [ ] 1.4 Синхронизировать ML tab/scope, Devices filters/view, Alerts filters/view и Maintenance range/view с URL и Back.
- [ ] 1.5 Создать route/tab dirty-draft guard с Keep/Discard и scoped recovery; не ломать существующие Dialog guards.
- [ ] 1.6 Обернуть allowed persistence в typed helpers с corrupt-data fallback и logout isolation.
- [ ] 1.7 Добавить assistant context adapters для ML, inventory, incidents и maintenance с человеческим preview и актуальными prompts.
- [ ] 1.8 Проверить simultaneous 401, retry failure, external returnTo, Back/reload и account switch.
- [ ] 1.9 Проверить секреты отсутствуют в URL/local storage/assistant payload; route change не теряет draft молча.
- [ ] 1.10 Провести live session expiry и recovery на deep link плюс workflow navigation keyboard checks.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
