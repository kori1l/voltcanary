# Tasks: Результат команд, локальный pending и понятные ошибки

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- improve-console-command-feedback`, затем соответствующие
project surface skills. Покрытие: R14, R16, R27.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Определить command outcome machine и typed API error shape без секретов.
- [ ] 1.2 Разделить createNotificationChannel mutation и list reload; сохранить committed ID до любого refresh.
- [ ] 1.3 Применить outcome split к затронутым create/update/lifecycle flows и unknown transport outcome reconciliation.
- [ ] 1.4 Добавить per-row/action pending guard к Ack/Resolve/Enable/Disable/Cancel/Retry/Approve.
- [ ] 1.5 Сохранить confirmation reason/impact и cancellation-without-request semantics.
- [ ] 1.6 Отобразить field/auth/permission/network/dependency errors и пользовательский next step.
- [ ] 1.7 Добавить clipboard success/error feedback и accurate recovery delivery copy.
- [ ] 1.8 Проверить successful mutation + failed reload, ambiguous create transport, double click и независимую другую строку.
- [ ] 1.9 Проверить sanitizer против provider URLs/token values и 401 не маскируется service outage.
- [ ] 1.10 Провести keyboard/browser checks уведомлений и ML row actions.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
