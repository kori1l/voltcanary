# Tasks: Исторические метрики, значимые точки и доступные графики

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- improve-telemetry-history-and-readable-evidence`, затем соответствующие
project surface skills. Покрытие: R19, R20, R23.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Проверить current metrics endpoint interval coverage; определить additive from/to metadata contract при необходимости.
- [ ] 1.2 Развязать history loader с latest-only metric keys и latest request failures.
- [ ] 1.3 Реализовать pure min/max sampling envelope и explicit threshold/gap behavior при превышении display budget.
- [ ] 1.4 Показывать raw/display point counts и выбранный aggregation mode.
- [ ] 1.5 Добавить text summary и keyboard-accessible metric/event table с cursor continuation.
- [ ] 1.6 Переиспользовать export API без fetch всех points в browser и сохранить ownership/range guards.
- [ ] 1.7 Покрыть исчезнувшую latest metric, несколько peaks/trough, null gaps, zero и избыточные threshold crossings.
- [ ] 1.8 Проверить unit/timezone, keyboard analysis и text-only screen reader evidence.
- [ ] 1.9 При additive API выполнить telemetry Maven tests и обновить telemetry-api.md; mathematical sampling regressions покрыть frontend тестами.
- [ ] 1.10 Проверить dense 7d/30d в пределах существующей retention и мобильный detail dialog.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
