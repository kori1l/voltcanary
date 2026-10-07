# Tasks: Достоверные состояния ресурсов и свежесть данных

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- fix-console-evidence-state-transitions`, затем соответствующие
project surface skills. Покрытие: R01, R03, R04, R05, R09, R10, R15.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Зафиксировать state transition table включая зависимость, successful-empty, retry, scope switch, permission loss и superseded responses.
- [ ] 1.2 Вынести scope-bound resource/clock/polling helpers из страниц; сохранить существующие late-response guards.
- [ ] 1.3 Перевести inventory latest/risk на отдельные ресурсы по deviceId с local stale timestamps и retry.
- [ ] 1.4 Исправить оставшиеся Loading в Settings/preferences/Dashboard; сохранить актуальные исправления DeviceDetails.
- [ ] 1.5 Передать state/freshness в ML prerequisites и исключить empty-array fallback для unknown evidence.
- [ ] 1.6 Реализовать local loaders и группировку связанных ошибок вместо пяти глобальных Retry.
- [ ] 1.7 Разделить alert delivery-attempt loading/error/empty/ready; запретить No attempts после request failure.
- [ ] 1.8 Добавить off/30s/1m/5m read refresh, hidden pause, backoff и timer-based age updates.
- [ ] 1.9 Проверить clock advance без клика, retained risk, failed inventory dependency, A→B failure, attempts retry, revocation purge и late response.
- [ ] 1.10 Проверить реальные populated/error/stale секции на 390/768/1440 и documented resource-state transitions.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
