# Tasks: Версионированные тарифы и оценка стоимости энергии

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-tariff-cost-estimates`, затем соответствующие
project surface skills. Покрытие: W14, DB-17, DB-21, DB-26, DB-34.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Зафиксировать tariff domain ownership/schema/grants и decimal rounding contract.
- [ ] 1.2 Добавить versioned account tariff migrations и flat-rate effective period APIs.
- [ ] 1.3 Реализовать owner/tariff.read/write access и workspace optional adapter после membership readiness.
- [ ] 1.4 Определить taxes/fees/exchange exclusions и visible Estimate labels.
- [ ] 1.5 Реализовать energy segment×tariff interval cost calculation с source/coverage provenance.
- [ ] 1.6 Если timebands включаются, добавить timezone/DST/rate overlap validation; иначе capability остаётся off.
- [ ] 1.7 Подключить W14 catalog/config/preview и missing tariff/partial/mixedcurrency states.
- [ ] 1.8 Добавить immutable tariff revisions/archive и auditable backdated correction behavior.
- [ ] 1.9 Проверить known energy×rate, revision boundary, missing tariff, partialenergy, rounding, mixed currency и unauthorised cost reads.
- [ ] 1.10 Выполнить focused device/dashboard Maven и editor payload/visibility tests.
- [ ] 1.11 Обновить business estimate docs с явным non-billing/non-invoice scope.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
