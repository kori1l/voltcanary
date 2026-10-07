# Tasks: Workspace, участники и отдельные права на ресурсы

Все задачи плановые. Checkbox отмечается только после implementation и verification.
Перед кодом: `npm.cmd run openspec:apply -- add-workspace-resource-membership`, затем соответствующие
project surface skills. Покрытие: DB-33, DB-34, DB-35, DB-36, DB-37.

## 1. Contracts, Implementation And Focused Acceptance

- [ ] 1.1 Зафиксировать ADR identity membership/device resource-grant ownership и personal account compatibility.
- [ ] 1.2 Спроектировать workspace/team/member/resource-account association migrations и consent/active lifecycle.
- [ ] 1.3 Определить permission matrix по resource action/scope; запрет ADMIN/SUPPORT blanket business access.
- [ ] 1.4 Реализовать workspace/member/team APIs с existing-user checks и review без unsolicited email delivery.
- [ ] 1.5 Добавить owner-controlled account association и explicit device/location scope grants.
- [ ] 1.6 Реализовать common authorization port/request context с spoofed-header stripping и service-auth internal lookups.
- [ ] 1.7 Интегрировать grants в device/telemetry/analytics/alerts/maintenance reads и action-specific mutations без weakening existing owners.
- [ ] 1.8 Добавить grants version/fingerprint и revoke/disable lifecycle propagation с current checks перед cache.
- [ ] 1.9 Добавить минимальный reviewed membership/resources UI и audit/owner transfer workflow.
- [ ] 1.10 Покрыть crossworkspace IDs, team member subset, account association spoof, stale JWT/cache после revoke и lifecycle disable.
- [ ] 1.11 Проверить old personal ownership APIs/regression и domain action permissions без dashboard role inheritance.
- [ ] 1.12 Выполнить affected backend/common-security Maven tests, tenant isolation integration smoke и docs/API/event consistency.

## 2. Documentation And Final Gates

- [ ] 2.1 Согласовать implemented contracts/workflow с docs/current и target requirement mapping; не описывать ещё отсутствующее как current.
- [ ] 2.2 Выполнить frontend typecheck/test/build и перечисленные browser/workflow проверки; backend migrations/contracts дополнительно проверять focused Maven/integration командами при их изменении.
- [ ] 2.3 Выполнить `npm.cmd run openspec:validate` и `npm.cmd run structure:check`, сохранить acceptance evidence/limitations и сверить hard dependencies перед archive.
