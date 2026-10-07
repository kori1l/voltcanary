# Tasks: Бизнес-шаблоны и привязка к реальным данным

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-task-templates`, затем соответствующие
project surface skills. Покрытие: DB-03, DB-04, DB-05, DB-16, DB-17, DB-18, DB-22, W01, W02, W03, W04, W05, W06, W07, W08, W09, W10, W12, W13.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Определить versioned template DTO и symbolic binding schema без owner/device UUID/secrets.
- [ ] 1.2 Реализовать authorized template catalog/capability discovery и task search/thumbnails.
- [ ] 1.3 Создать Shift/My devices/Refrigeration/Technician definitions с конкретными defaults/layout/drill-down.
- [ ] 1.4 Добавить Energy template only-if-capability после energy aggregate change, иначе explanation без fake KPI.
- [ ] 1.5 Добавить binding wizard location/device slots, current scope count и missing temperature/assignment/metric warnings.
- [ ] 1.6 Поддержать explicit remove/keep unsupported panel и demo labels; template preview real authorised values.
- [ ] 1.7 Копировать template version в draft без live mutation при последующем обновлении шаблона.
- [ ] 1.8 Предложить personas/business task labels без obligatory signup questions и role upgrades.
- [ ] 1.9 Проверить absent temperature/tariff/cycle data, foreign UUID/import rebind и fallback template version.
- [ ] 1.10 Провести manager/technician/owner usability sessions с≤3min/≤90s targets и фиксацией неверных трактовок unknown/scope.
- [ ] 1.11 Обновить template/API/docs examples и reference demos только после настоящей интеграции.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
