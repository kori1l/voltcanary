# Tasks: Контекст навигации, восстановление сессии и черновики

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- improve-console-context-and-session-recovery`, затем соответствующие
project surface skills. Покрытие: R12, R13, R17, R28.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Определить typed query schemas и безопасный returnTo с тестами external URL/auth loop rejection.
- [x] 1.2 Добавить single-flight refresh и единое событие session expiration в API layer.
- [x] 1.3 Обновить RequireAuth/Login для восстановления device/incident/metric/from/to route.
- [x] 1.4 Синхронизировать ML tab/scope, Devices filters/view, Alerts filters/view и Maintenance range/view с URL и Back.
- [x] 1.5 Создать route/tab dirty-draft guard с Keep/Discard и scoped recovery; не ломать существующие Dialog guards.
- [x] 1.6 Обернуть allowed persistence в typed helpers с corrupt-data fallback и logout isolation.
- [x] 1.7 Добавить assistant context adapters для ML, inventory, incidents и maintenance с человеческим preview и актуальными prompts.
- [x] 1.8 Проверить simultaneous 401, retry failure, external returnTo, Back/reload и account switch.
- [x] 1.9 Проверить секреты отсутствуют в URL/local storage/assistant payload; route change не теряет draft молча.
- [x] 1.10 Провести live session expiry и recovery на deep link плюс workflow navigation keyboard checks.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [x] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.

Acceptance 2026-10-08: frontend typecheck, 41 files / 132 tests, production build; strict OpenSpec 40/40 and structure pass. Live local admin session: expired access refresh, definitive refresh rejection, safe ML deep-link sign-in recovery, keyboard Keep/Discard. Existing bundle-size advisory and structure warnings remain. No backend contract/migration changes or business mutations in this acceptance. Credentials excluded from draft/URL/assistant fields; existing dedicated auth-token storage remains unchanged.
