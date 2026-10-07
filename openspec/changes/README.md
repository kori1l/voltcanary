# UI/UX и пользовательские дашборды: active OpenSpec program

Дата: 7 октября 2026. **16 proposed changes; implementation не выполнена.**
48 delta requirements, 87 GIVEN/WHEN/THEN scenarios, 225 unchecked tasks.
Числа описывают этот план, не выполненные проверки или завершённые feature.

Источники: [UI/UX audit](../../docs/target/ui-design-requirements.md),
[custom dashboard requirements](../../docs/target/custom-dashboards.md).
Этот индекс владеет change selection/dependencies/coverage; документы target
владеют продуктовой мотивацией. Архивированные UI changes не возобновляются.

## Changes And Dependencies

| № | Change | Результат | Этап | Hard dependencies |
| --- | --- | --- | --- | --- |
| 1 | [fix-console-evidence-state-transitions](fix-console-evidence-state-transitions/proposal.md) | Достоверные состояния ресурсов и свежесть данных | UI P0 | Baseline |
| 2 | [fix-ml-pipeline-lineage-readiness](fix-ml-pipeline-lineage-readiness/proposal.md) | Готовность ML по выбранной цепочке данных | UI P0 | `fix-console-evidence-state-transitions` |
| 3 | [improve-console-context-and-session-recovery](improve-console-context-and-session-recovery/proposal.md) | Контекст навигации, восстановление сессии и черновики | UI P1 | `fix-console-evidence-state-transitions` |
| 4 | [improve-fleet-browsing-and-device-activation](improve-fleet-browsing-and-device-activation/proposal.md) | Пагинация, экономный поиск и первое измерение | UI P1 | `fix-console-evidence-state-transitions`, `improve-console-context-and-session-recovery` |
| 5 | [improve-console-command-feedback](improve-console-command-feedback/proposal.md) | Результат команд, локальный pending и понятные ошибки | UI P1 | `fix-console-evidence-state-transitions` |
| 6 | [improve-telemetry-history-and-readable-evidence](improve-telemetry-history-and-readable-evidence/proposal.md) | Исторические метрики, значимые точки и доступные графики | UI P1 | `fix-console-evidence-state-transitions` |
| 7 | [improve-console-accessibility-and-page-delivery](improve-console-accessibility-and-page-delivery/proposal.md) | Доступность, мобильные рабочие экраны и загрузка по маршрутам | UI P1/P2 | `fix-console-evidence-state-transitions`, `improve-console-command-feedback`, `improve-console-context-and-session-recovery` |
| 8 | [add-private-dashboard-domain](add-private-dashboard-domain/proposal.md) | Серверные личные дашборды, версии и предпочтения | Dashboards 1 | Baseline |
| 9 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Типизированные запросы виджетов и полные агрегаты | Dashboards 1 | `add-private-dashboard-domain`, `fix-console-evidence-state-transitions`, `improve-telemetry-history-and-readable-evidence` |
| 10 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | Визуальный редактор и персональный стартовый экран | Dashboards 1 | `add-private-dashboard-domain`, `add-dashboard-widget-query-engine`, `improve-console-context-and-session-recovery`, `improve-console-accessibility-and-page-delivery` |
| 11 | [add-dashboard-task-templates](add-dashboard-task-templates/proposal.md) | Бизнес-шаблоны и привязка к реальным данным | Dashboards 1 | `add-dashboard-visual-editor`, `add-dashboard-widget-query-engine` |
| 12 | [add-workspace-resource-membership](add-workspace-resource-membership/proposal.md) | Workspace, участники и отдельные права на ресурсы | Dashboards 2 prerequisite | `add-private-dashboard-domain` |
| 13 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | Командные дашборды, публикация и совместный доступ | Dashboards 2 | `add-workspace-resource-membership`, `add-private-dashboard-domain`, `add-dashboard-widget-query-engine`, `add-dashboard-visual-editor` |
| 14 | [add-dashboard-energy-aggregates](add-dashboard-energy-aggregates/proposal.md) | Расход энергии, покрытие и защита от двойного счёта | Dashboards 1 extension | `add-dashboard-widget-query-engine` |
| 15 | [add-dashboard-tariff-cost-estimates](add-dashboard-tariff-cost-estimates/proposal.md) | Версионированные тарифы и оценка стоимости энергии | Dashboards 3 | `add-dashboard-energy-aggregates`, `add-dashboard-visual-editor` |
| 16 | [add-dashboard-operational-period-metrics](add-dashboard-operational-period-metrics/proposal.md) | Рабочие расписания, подтверждённые циклы и простой | Dashboards 3 | `add-dashboard-widget-query-engine`, `add-dashboard-energy-aggregates` |

Номера — ссылки в программе, не требование выполнять всё строго по очереди.
Изменения backend private domain можно готовить независимо от UI, но его
интеграцию принимают только после соответствующих queries/editor prerequisites.

## Recommended Delivery Order

1. Evidence states (P0), затем selected ML lineage. Context/session, action
   feedback, fleet browsing и telemetry evidence опираются на общую модель.
   Accessibility/page delivery интегрируется после context/action feedback.
2. Private dashboard domain → typed query engine → visual editor → templates.
   Сохраняется системный overview fallback и USER access без ADMIN upgrade.
   Energy aggregates дополняют query engine; Energy template сначала явно
   capability-gated, затем становится готовым. W14/W15 не выдумывают данные.
3. Workspace resource membership → team publication. Не объединять это с
   приватным MVP и не разрешать sharing до downstream grants enforcement.
4. Verified energy → tariff estimates; operating schedules/confirmed cycle
   facts → operational metrics. Workspace tariff adapter additionally требует
   membership; private tariffs этого не требуют. Billing/OEE/orders/anonymous
   embed/AI-generated dashboards вне scope.

## Requirement Ownership Rules

- Primary owner ниже отвечает за contract и основную acceptance; supporting
  changes интегрируют его, а не создают конкурирующий source of truth.
- В shared main specs используются уникальные **ADDED Requirements**. Existing
  baseline не переписан как будто ещё не реализован; archive delta requirements
  следует последовательно после fulfilled dependencies.
- DB-39 split: private CRUD/revisions/preferences у private domain; query/types
  endpoints у query engine; templates у task templates; grants у team publication.
- DB-08/09/10 split: server versions/conflicts у domain, autosave/editor UX у
  visual editor. DB-33 private ownership у domain, shared membership у workspace,
  dashboard grants у team. DB-43 domain/service structure у domain, feature module
  boundaries у editor, schemas/query cache у engine.
- Никакой task не закрывается созданием этого proposal. Перед implementation:
  `npm.cmd run openspec:apply -- <change-id>`, read proposal/design/tasks/specs,
  relevant project skills. Archive только после кода, docs и acceptance evidence.
- Права, full-data aggregates, unknown/stale и mathematical provenance — release
  gates. Нагрузка/latency targets подтверждаются evidence, не schema validation.

## UI/UX R01–R28 Coverage

| Audit ID | Primary owner | Supporting integration |
| --- | --- | --- |
| R01 | [fix-console-evidence-state-transitions](fix-console-evidence-state-transitions/proposal.md) | — |
| R02 | [fix-ml-pipeline-lineage-readiness](fix-ml-pipeline-lineage-readiness/proposal.md) | — |
| R03 | [fix-console-evidence-state-transitions](fix-console-evidence-state-transitions/proposal.md) | — |
| R04 | [fix-console-evidence-state-transitions](fix-console-evidence-state-transitions/proposal.md) | — |
| R05 | [fix-console-evidence-state-transitions](fix-console-evidence-state-transitions/proposal.md) | — |
| R06 | [improve-fleet-browsing-and-device-activation](improve-fleet-browsing-and-device-activation/proposal.md) | — |
| R07 | [improve-fleet-browsing-and-device-activation](improve-fleet-browsing-and-device-activation/proposal.md) | — |
| R08 | [improve-fleet-browsing-and-device-activation](improve-fleet-browsing-and-device-activation/proposal.md) | — |
| R09 | [fix-console-evidence-state-transitions](fix-console-evidence-state-transitions/proposal.md) | — |
| R10 | [fix-console-evidence-state-transitions](fix-console-evidence-state-transitions/proposal.md) | — |
| R11 | [improve-console-accessibility-and-page-delivery](improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R12 | [improve-console-context-and-session-recovery](improve-console-context-and-session-recovery/proposal.md) | — |
| R13 | [improve-console-context-and-session-recovery](improve-console-context-and-session-recovery/proposal.md) | — |
| R14 | [improve-console-command-feedback](improve-console-command-feedback/proposal.md) | — |
| R15 | [fix-console-evidence-state-transitions](fix-console-evidence-state-transitions/proposal.md) | — |
| R16 | [improve-console-command-feedback](improve-console-command-feedback/proposal.md) | — |
| R17 | [improve-console-context-and-session-recovery](improve-console-context-and-session-recovery/proposal.md) | — |
| R18 | [improve-fleet-browsing-and-device-activation](improve-fleet-browsing-and-device-activation/proposal.md) | — |
| R19 | [improve-telemetry-history-and-readable-evidence](improve-telemetry-history-and-readable-evidence/proposal.md) | — |
| R20 | [improve-telemetry-history-and-readable-evidence](improve-telemetry-history-and-readable-evidence/proposal.md) | — |
| R21 | [improve-console-accessibility-and-page-delivery](improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R22 | [improve-console-accessibility-and-page-delivery](improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R23 | [improve-telemetry-history-and-readable-evidence](improve-telemetry-history-and-readable-evidence/proposal.md) | — |
| R24 | [improve-console-accessibility-and-page-delivery](improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R25 | [improve-console-accessibility-and-page-delivery](improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R26 | [improve-console-accessibility-and-page-delivery](improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R27 | [improve-console-command-feedback](improve-console-command-feedback/proposal.md) | — |
| R28 | [improve-console-context-and-session-recovery](improve-console-context-and-session-recovery/proposal.md) | — |

## Dashboard DB-01–DB-43 Coverage

| Product requirement | Primary owner | Supporting integration |
| --- | --- | --- |
| DB-01 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | — |
| DB-02 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | `add-team-dashboard-publication` |
| DB-03 | [add-dashboard-task-templates](add-dashboard-task-templates/proposal.md) | `add-dashboard-visual-editor` |
| DB-04 | [add-dashboard-task-templates](add-dashboard-task-templates/proposal.md) | — |
| DB-05 | [add-dashboard-task-templates](add-dashboard-task-templates/proposal.md) | `add-dashboard-visual-editor`, `add-team-dashboard-publication` |
| DB-06 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | — |
| DB-07 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | — |
| DB-08 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | `add-private-dashboard-domain` |
| DB-09 | [add-private-dashboard-domain](add-private-dashboard-domain/proposal.md) | `add-dashboard-visual-editor` |
| DB-10 | [add-private-dashboard-domain](add-private-dashboard-domain/proposal.md) | `add-dashboard-visual-editor`, `add-team-dashboard-publication` |
| DB-11 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | `add-team-dashboard-publication` |
| DB-12 | [add-private-dashboard-domain](add-private-dashboard-domain/proposal.md) | `add-dashboard-visual-editor` |
| DB-13 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | — |
| DB-14 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | — |
| DB-15 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | — |
| DB-16 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-task-templates` |
| DB-17 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-task-templates`, `add-dashboard-tariff-cost-estimates`, `add-dashboard-operational-period-metrics` |
| DB-18 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-task-templates` |
| DB-19 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | — |
| DB-20 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-operational-period-metrics` |
| DB-21 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-energy-aggregates`, `add-dashboard-tariff-cost-estimates`, `add-dashboard-operational-period-metrics` |
| DB-22 | [add-private-dashboard-domain](add-private-dashboard-domain/proposal.md) | `add-dashboard-visual-editor`, `add-dashboard-task-templates`, `add-team-dashboard-publication` |
| DB-23 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-energy-aggregates` |
| DB-24 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | — |
| DB-25 | [add-dashboard-energy-aggregates](add-dashboard-energy-aggregates/proposal.md) | — |
| DB-26 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-energy-aggregates`, `add-dashboard-tariff-cost-estimates`, `add-dashboard-operational-period-metrics` |
| DB-27 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-energy-aggregates`, `add-dashboard-operational-period-metrics` |
| DB-28 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-visual-editor` |
| DB-29 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-visual-editor` |
| DB-30 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | `add-dashboard-widget-query-engine` |
| DB-31 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | `add-dashboard-widget-query-engine`, `add-team-dashboard-publication` |
| DB-32 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-visual-editor` |
| DB-33 | [add-private-dashboard-domain](add-private-dashboard-domain/proposal.md) | `add-workspace-resource-membership` |
| DB-34 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | `add-workspace-resource-membership`, `add-dashboard-tariff-cost-estimates` |
| DB-35 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | `add-workspace-resource-membership` |
| DB-36 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | `add-workspace-resource-membership` |
| DB-37 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | `add-workspace-resource-membership` |
| DB-38 | [add-private-dashboard-domain](add-private-dashboard-domain/proposal.md) | — |
| DB-39 | [add-private-dashboard-domain](add-private-dashboard-domain/proposal.md) | `add-dashboard-widget-query-engine` |
| DB-40 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | `add-private-dashboard-domain` |
| DB-41 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | — |
| DB-42 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | — |
| DB-43 | [add-dashboard-visual-editor](add-dashboard-visual-editor/proposal.md) | `add-private-dashboard-domain`, `add-dashboard-widget-query-engine` |

## Widget W01–W15 Coverage

| Widget | Query/domain owner | Release dependency |
| --- | --- | --- |
| W01 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W02 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W03 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W04 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W05 | [add-dashboard-energy-aggregates](add-dashboard-energy-aggregates/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W06 | [add-dashboard-energy-aggregates](add-dashboard-energy-aggregates/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W07 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W08 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W09 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W10 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W11 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Single-account matrix в query engine; workspace scope после team publication. |
| W12 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W13 | [add-dashboard-widget-query-engine](add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W14 | [add-dashboard-tariff-cost-estimates](add-dashboard-tariff-cost-estimates/proposal.md) | Stage 3; не притворяется готовым widget в первом релизе. |
| W15 | [add-dashboard-operational-period-metrics](add-dashboard-operational-period-metrics/proposal.md) | Stage 3; не притворяется готовым widget в первом релизе. |

## Validation And Scope

Для этого planning request выполняются OpenSpec strict validation, dependency
DAG/coverage/artifact/link checks и structure check. Они подтверждают связность
предложений, а не execution UX/runtime/capacity. Future implementation выполняет
focused frontend/Maven/integration/browser checks из каждого tasks.md; ML
production smoke нужен только когда соответствующий runtime/lifecycle/feature
contract действительно меняется. Evidence сохраняется в ignored artifacts.

В созданных changes отсутствуют completed checkboxes. Main specs и application
code для этой подготовки не менялись. AGENTS.md/skills не меняются: workflow
остаётся прежним.
