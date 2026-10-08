# Tasks: Достоверные состояния ресурсов и свежесть данных

Implementation и UI/live acceptance завершены 2026-10-08. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- fix-console-evidence-state-transitions`, затем соответствующие
project surface skills. Покрытие: R01, R03, R04, R05, R09, R10, R15.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Зафиксировать state transition table включая зависимость, successful-empty, retry, scope switch, permission loss и superseded responses.
- [x] 1.2 Вынести scope-bound resource/clock/polling helpers из страниц; сохранить существующие late-response guards.
- [x] 1.3 Перевести inventory latest/risk на отдельные ресурсы по deviceId с local stale timestamps и retry.
- [x] 1.4 Исправить оставшиеся Loading в Settings/preferences/Dashboard; сохранить актуальные исправления DeviceDetails.
- [x] 1.5 Передать state/freshness в ML prerequisites и исключить empty-array fallback для unknown evidence.
- [x] 1.6 Реализовать local loaders и группировку связанных ошибок вместо пяти глобальных Retry.
- [x] 1.7 Разделить alert delivery-attempt loading/error/empty/ready; запретить No attempts после request failure.
- [x] 1.8 Добавить off/30s/1m/5m read refresh, hidden pause, backoff и timer-based age updates.
- [x] 1.9 Проверить clock advance без клика, retained risk, failed inventory dependency, A→B failure, attempts retry, revocation purge и late response.
- [x] 1.10 Проверить реальные populated/error/stale секции на 390/768/1440 и documented resource-state transitions.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [x] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.

## Acceptance evidence

Frontend typecheck/test/build: 33 files / 92 tests pass. OpenSpec: 42 strict items pass. Structure passes with existing backend/Python warnings. Live 20 devices / 6 alerts at 390/768/1440; controlled read failures in ignored harness, local retry, retained risk and attempts error-to-empty recovery verified. Hook/workflow tests cover scope switch, obsolete responses, 401/403 purge, dependency failure and clock/polling. Details and limitations: docs/current/flows/console-interface.md, scoped evidence transitions. No backend contracts or mutations changed; no runtime delivery/capacity claim.
