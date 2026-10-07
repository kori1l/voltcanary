# Tasks: Доступность, мобильные рабочие экраны и загрузка по маршрутам

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- improve-console-accessibility-and-page-delivery`, затем соответствующие
project surface skills. Покрытие: R11, R21, R22, R24, R25, R26.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Подготовить desktop/mobile prototype navigation и populated inventory/ML sections с 200% text.
- [ ] 1.2 Реализовать shared Tabs и интегрировать device/ML workflows с keyboard semantics.
- [ ] 1.3 Связать status/error/hints и дать entity-aware имена row actions.
- [ ] 1.4 Убрать nested button-role у Agenda; keyboard Enter на Cancel не открывает родителя.
- [ ] 1.5 Исправить required marker и neutral unavailable tokens, undefined variables и затронутые CSS overrides.
- [ ] 1.6 Обновить task terminology ML workspace/Acknowledge без обещания Production ready; сохранить route URLs.
- [ ] 1.7 Добавить mobile inventory cards/compact rows и discoverable ML section control по результату прототипа.
- [ ] 1.8 Разделить route chunks через lazy loading, устойчивые fallback и explicit chunk retry.
- [ ] 1.9 Проверить keyboard/screen reader, 360/390/768/1024/1280/1440 и native 200% text с длинными данными.
- [ ] 1.10 Сравнить build chunks и initial auth requests до/после; не заявлять latency improvement только по size.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
