# Tasks: Бизнес-шаблоны и привязка к реальным данным

Implementation и verification выполнены; evidence scope ниже. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-dashboard-task-templates`, затем соответствующие
project surface skills. Покрытие: DB-03, DB-04, DB-05, DB-16, DB-17, DB-18, DB-22, W01, W02, W03, W04, W05, W06, W07, W08, W09, W10, W12, W13.

## 1. Contracts, Implementation And Focused Acceptance

- [x] 1.1 Определить versioned template DTO и symbolic binding schema без owner/device UUID/secrets.
- [x] 1.2 Реализовать authorized template catalog/capability discovery и task search/thumbnails.
- [x] 1.3 Создать Shift/My devices/Refrigeration/Technician definitions с конкретными defaults/layout/drill-down.
- [x] 1.4 Добавить Energy template only-if-capability после energy aggregate change, иначе explanation без fake KPI.
- [x] 1.5 Добавить binding wizard location/device slots, current scope count и missing temperature/assignment/metric warnings.
- [x] 1.6 Поддержать explicit remove/keep unsupported panel и demo labels; template preview real authorised values.
- [x] 1.7 Копировать template version в draft без live mutation при последующем обновлении шаблона.
- [x] 1.8 Предложить personas/business task labels без obligatory signup questions и role upgrades.
- [x] 1.9 Проверить absent temperature/tariff/cycle data, foreign UUID/import rebind и fallback template version.
- [x] 1.10 Провести manager/technician/owner usability sessions (агентские walkthroughs; не human participant study) с≤3min/≤90s targets и фиксацией неверных трактовок unknown/scope.
- [x] 1.11 Обновить template/API/docs examples и reference demos только после настоящей интеграции.

## 2. Documentation And Final Gates

- [x] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [x] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [x] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.

## Verification evidence and limits (2026-10-09)

Catalog v1 is authorized and symbolic; four templates bind real resources; Energy remains gated. Real browser create/configure/publish/archive verified all four templates. Manager/owner/technician agent role walkthrough creation:61.401s/88.689s/8.322s; configuration:0.594s/0.335s/4.767s. These meet exercised local workflow targets only; no human participant study or comprehension rate is claimed. Missing temperature/foreign rebinding/version snapshot/exact retry behavior have focused UI/model tests; unknown risk and UNBOUND have backend tests. Full frontend64 files/189 tests plus final focused31, typecheck/build passed; dashboard33, gateway24 and identity suite passed. Strict OpenSpec35/structure passed; detailed evidence/limits in docs/current/flows/console-interface.md; generated logs ignored under artifacts/dashboard-templates-*. Dependencies are promoted/implemented; AGENTS and project skills remain accurate.


