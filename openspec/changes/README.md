# UI/UX и пользовательские дашборды: active OpenSpec program

Дата: 7 октября 2026; checkpoint 2026-10-09. **Программа из 16 changes: первые семь, private dashboard domain и widget query engine закрыты и архивированы (UTC archive date 2026-10-08). Accessibility/page delivery принят по указанию пользователя с исключением непроведённых native zoom/speech проверок.**
48 delta requirements, 87 GIVEN/WHEN/THEN scenarios, 225 задач исходного плана; 152 задачи закрыты: 78 в первых шести, 13 в accessibility/page delivery (две с явно документированным исключением приёмки), 15 в private dashboard domain 16 в widget query engine и 16 в visual editor и 14 в task templates. 2026-10-09 пользователь возобновил выполнение активной очереди по порядку, затем разрешил закрыть и архивировать accessibility с ограничениями инструментов. Visual editor реализован, проверен и архивирован 2026-10-09. Task templates реализован, проверен и архивирован 2026-10-09. По уточнению пользователя приоритет перед energy — набор именованных рабочих экранов и явное размещение виджетов. Native проверки не считаются пройденными; процедура отложенной проверки приведена в docs/current/flows/console-interface.md.
Числа описывают этот план, не выполненные проверки или завершённые feature.

Источники: [UI/UX audit](../../docs/target/ui-design-requirements.md),
[custom dashboard requirements](../../docs/target/custom-dashboards.md).
Этот индекс владеет dependencies/coverage; документы target владеют продуктовой
мотивацией. Порядок выбора закреплён в
[навыке voltcanary-openspec](../../.codex/skills/voltcanary-openspec/SKILL.md)
и его [execution sequence](../../.codex/skills/voltcanary-openspec/references/ui-dashboard-sequence.md).
Архивированные UI changes не возобновляются.

## Changes And Dependencies

Следующее уточнение пользователя — убрать текст из графиков и ещё уменьшить
верхнюю панель — выполнено:
[минимальные подписи панелей](archive/2026-10-09-minimal-dashboard-presentation/proposal.md).
Четыре дополнительные задачи закрыты отдельно от исходного плана. Вместо
вкладок используется селектор экранов; заголовки, легенды, пояснения, сравнения
и подробности включаются в настройках. Состояния stale/partial/estimate/denied
сохраняются. Desktop холст начинается на80px, mobile со скрытой навигацией
на144px. Проверены сохранение опций и reload; frontend212 тестов,
typecheck/build, OpenSpec39 и structure прошли. Исходные energy и последующие
changes продолжают оставаться активными.

Уточнение компактного рабочего места выполнено:
[холст, правая панель и скрываемая навигация](archive/2026-10-09-compact-dashboard-workspace/proposal.md).
Ещё четыре дополнительные задачи закрыты отдельно от исходной программы.
Верхняя панель компактна; настройки открываются справа; левую навигацию можно
скрыть и восстановить. Browser: desktop панели начинаются на177px,
mobile390px — на232px без горизонтального overflow. Frontend210 тестов,
финальные35 dashboard/dialog тестов, typecheck/build и strict/structure прошли.

Приоритетное исправление перемещения карточек выполнено и архивировано:
[перемещение на холсте](archive/2026-10-09-improve-dashboard-card-movement/proposal.md).
Четыре дополнительные задачи закрыты отдельно от исходных 225: ручка Drag,
подсветка направления, отмена, видимые кнопки, независимые раскладки и
сохранение/перезагрузка. Полный frontend: 70 файлов, 209 тестов; после финальных
уточнений 32 dashboard-теста, typecheck и build прошли. Pointer-проверки
автоматические; физический drag в DOM-only браузере не проводился.
Energy и следующие исходные changes остаются активными до завершения их
собственной приёмки.

Дополнительное уточнение пользователя от2026-10-09 выполнено и архивировано:
[набор рабочих экранов и явное размещение](archive/2026-10-09-improve-dashboard-screen-navigation-and-placement/proposal.md).
Его10 задач закрыты отдельно от225 задач исходной программы. Monitoring,
Analytics и Incidents имеют свои панели; legacy, публикация, клавиатурный
перенос и независимая mobile-раскладка проверены. Backend35, frontend200,
typecheck/build, strict OpenSpec36 и structure прошли. Приоритетное уточнение
завершено перед продолжением очереди с `add-dashboard-energy-aggregates`.

| № | Change | Результат | Этап | Hard dependencies |
| --- | --- | --- | --- | --- |
| 1 | [fix-console-evidence-state-transitions](archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) | Достоверные состояния ресурсов и свежесть данных | UI P0 | Baseline |
| 2 | [fix-ml-pipeline-lineage-readiness](archive/2026-10-08-fix-ml-pipeline-lineage-readiness/proposal.md) | Готовность ML по выбранной цепочке данных | UI P0 | `fix-console-evidence-state-transitions` |
| 3 | [improve-console-context-and-session-recovery](archive/2026-10-08-improve-console-context-and-session-recovery/proposal.md) | Контекст навигации, восстановление сессии и черновики | UI P1 | `fix-console-evidence-state-transitions` |
| 4 | [improve-fleet-browsing-and-device-activation](archive/2026-10-08-improve-fleet-browsing-and-device-activation/proposal.md) | Пагинация, экономный поиск и первое измерение | UI P1 | `fix-console-evidence-state-transitions`, `improve-console-context-and-session-recovery` |
| 5 | [improve-console-command-feedback](archive/2026-10-08-improve-console-command-feedback/proposal.md) | Результат команд, локальный pending и понятные ошибки | UI P1 | `fix-console-evidence-state-transitions` |
| 6 | [improve-telemetry-history-and-readable-evidence](archive/2026-10-08-improve-telemetry-history-and-readable-evidence/proposal.md) | Исторические метрики, значимые точки и доступные графики | UI P1 | `fix-console-evidence-state-transitions` |
| 7 | [improve-console-accessibility-and-page-delivery](archive/2026-10-08-improve-console-accessibility-and-page-delivery/proposal.md) | Доступность, мобильные рабочие экраны и загрузка по маршрутам | UI P1/P2 | `fix-console-evidence-state-transitions`, `improve-console-command-feedback`, `improve-console-context-and-session-recovery` |
| 8 | [add-private-dashboard-domain](archive/2026-10-08-add-private-dashboard-domain/proposal.md) | Серверные личные дашборды, версии и предпочтения | Dashboards 1 | Baseline |
| 9 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Типизированные запросы виджетов и полные агрегаты | Dashboards 1 | `add-private-dashboard-domain`, `fix-console-evidence-state-transitions`, `improve-telemetry-history-and-readable-evidence` |
| 10 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | Визуальный редактор и персональный стартовый экран | Dashboards 1 | `add-private-dashboard-domain`, `add-dashboard-widget-query-engine`, `improve-console-context-and-session-recovery`, `improve-console-accessibility-and-page-delivery` |
| 11 | [add-dashboard-task-templates](archive/2026-10-09-add-dashboard-task-templates/proposal.md) | Бизнес-шаблоны и привязка к реальным данным | Dashboards 1 | `add-dashboard-visual-editor`, `add-dashboard-widget-query-engine` |
| 12 | [add-workspace-resource-membership](add-workspace-resource-membership/proposal.md) | Workspace, участники и отдельные права на ресурсы | Dashboards 2 prerequisite | `add-private-dashboard-domain` |
| 13 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | Командные дашборды, публикация и совместный доступ | Dashboards 2 | `add-workspace-resource-membership`, `add-private-dashboard-domain`, `add-dashboard-widget-query-engine`, `add-dashboard-visual-editor` |
| 14 | [add-dashboard-energy-aggregates](add-dashboard-energy-aggregates/proposal.md) | Расход энергии, покрытие и защита от двойного счёта | Dashboards 1 extension | `add-dashboard-widget-query-engine` |
| 15 | [add-dashboard-tariff-cost-estimates](add-dashboard-tariff-cost-estimates/proposal.md) | Версионированные тарифы и оценка стоимости энергии | Dashboards 3 | `add-dashboard-energy-aggregates`, `add-dashboard-visual-editor` |
| 16 | [add-dashboard-operational-period-metrics](add-dashboard-operational-period-metrics/proposal.md) | Рабочие расписания, подтверждённые циклы и простой | Dashboards 3 | `add-dashboard-widget-query-engine`, `add-dashboard-energy-aggregates` |

Номера — ссылки в программе, не требование выполнять всё строго по очереди.
Изменения backend private domain можно готовить независимо от UI, но его
интеграцию принимают только после соответствующих queries/editor prerequisites.

## Fixed Execution Policy

С 8 октября 2026 default execution queue находится в
[reference проектного OpenSpec-навыка](../../.codex/skills/voltcanary-openspec/references/ui-dashboard-sequence.md).
Это единственный полный упорядоченный список; номера таблицы выше — ID
программы, а не позиции очереди.

Общий маршрут: P0 truth/ML → контекст и команды → fleet/telemetry/accessibility →
private domain/queries/editor/templates → проверенные energy aggregates →
workspace membership/team publication → tariff/operational metrics.
При запросе «следующий» навык продолжает начатый active change либо выбирает
первый незавершённый по подходящей очереди и его prerequisites. Dashboard-only
запрос включает необходимые UI prerequisites; private MVP не ждёт командных прав.
Energy template capability-gated до настоящих aggregates. Явный выбор пользователя
имеет приоритет; проверка hard dependencies сохраняется.

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
| R01 | [fix-console-evidence-state-transitions](archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) | — |
| R02 | [fix-ml-pipeline-lineage-readiness](archive/2026-10-08-fix-ml-pipeline-lineage-readiness/proposal.md) | — |
| R03 | [fix-console-evidence-state-transitions](archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) | — |
| R04 | [fix-console-evidence-state-transitions](archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) | — |
| R05 | [fix-console-evidence-state-transitions](archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) | — |
| R06 | [improve-fleet-browsing-and-device-activation](archive/2026-10-08-improve-fleet-browsing-and-device-activation/proposal.md) | — |
| R07 | [improve-fleet-browsing-and-device-activation](archive/2026-10-08-improve-fleet-browsing-and-device-activation/proposal.md) | — |
| R08 | [improve-fleet-browsing-and-device-activation](archive/2026-10-08-improve-fleet-browsing-and-device-activation/proposal.md) | — |
| R09 | [fix-console-evidence-state-transitions](archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) | — |
| R10 | [fix-console-evidence-state-transitions](archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) | — |
| R11 | [improve-console-accessibility-and-page-delivery](archive/2026-10-08-improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R12 | [improve-console-context-and-session-recovery](archive/2026-10-08-improve-console-context-and-session-recovery/proposal.md) | — |
| R13 | [improve-console-context-and-session-recovery](archive/2026-10-08-improve-console-context-and-session-recovery/proposal.md) | — |
| R14 | [improve-console-command-feedback](archive/2026-10-08-improve-console-command-feedback/proposal.md) | — |
| R15 | [fix-console-evidence-state-transitions](archive/2026-10-08-fix-console-evidence-state-transitions/proposal.md) | — |
| R16 | [improve-console-command-feedback](archive/2026-10-08-improve-console-command-feedback/proposal.md) | — |
| R17 | [improve-console-context-and-session-recovery](archive/2026-10-08-improve-console-context-and-session-recovery/proposal.md) | — |
| R18 | [improve-fleet-browsing-and-device-activation](archive/2026-10-08-improve-fleet-browsing-and-device-activation/proposal.md) | — |
| R19 | [improve-telemetry-history-and-readable-evidence](archive/2026-10-08-improve-telemetry-history-and-readable-evidence/proposal.md) | — |
| R20 | [improve-telemetry-history-and-readable-evidence](archive/2026-10-08-improve-telemetry-history-and-readable-evidence/proposal.md) | — |
| R21 | [improve-console-accessibility-and-page-delivery](archive/2026-10-08-improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R22 | [improve-console-accessibility-and-page-delivery](archive/2026-10-08-improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R23 | [improve-telemetry-history-and-readable-evidence](archive/2026-10-08-improve-telemetry-history-and-readable-evidence/proposal.md) | — |
| R24 | [improve-console-accessibility-and-page-delivery](archive/2026-10-08-improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R25 | [improve-console-accessibility-and-page-delivery](archive/2026-10-08-improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R26 | [improve-console-accessibility-and-page-delivery](archive/2026-10-08-improve-console-accessibility-and-page-delivery/proposal.md) | — |
| R27 | [improve-console-command-feedback](archive/2026-10-08-improve-console-command-feedback/proposal.md) | — |
| R28 | [improve-console-context-and-session-recovery](archive/2026-10-08-improve-console-context-and-session-recovery/proposal.md) | — |

## Dashboard DB-01–DB-43 Coverage

| Product requirement | Primary owner | Supporting integration |
| --- | --- | --- |
| DB-01 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | — |
| DB-02 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | `add-team-dashboard-publication` |
| DB-03 | [add-dashboard-task-templates](archive/2026-10-09-add-dashboard-task-templates/proposal.md) | `add-dashboard-visual-editor` |
| DB-04 | [add-dashboard-task-templates](archive/2026-10-09-add-dashboard-task-templates/proposal.md) | — |
| DB-05 | [add-dashboard-task-templates](archive/2026-10-09-add-dashboard-task-templates/proposal.md) | `add-dashboard-visual-editor`, `add-team-dashboard-publication` |
| DB-06 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | — |
| DB-07 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | — |
| DB-08 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | `add-private-dashboard-domain` |
| DB-09 | [add-private-dashboard-domain](archive/2026-10-08-add-private-dashboard-domain/proposal.md) | `add-dashboard-visual-editor` |
| DB-10 | [add-private-dashboard-domain](archive/2026-10-08-add-private-dashboard-domain/proposal.md) | `add-dashboard-visual-editor`, `add-team-dashboard-publication` |
| DB-11 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | `add-team-dashboard-publication` |
| DB-12 | [add-private-dashboard-domain](archive/2026-10-08-add-private-dashboard-domain/proposal.md) | `add-dashboard-visual-editor` |
| DB-13 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | — |
| DB-14 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | — |
| DB-15 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | — |
| DB-16 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-task-templates` |
| DB-17 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-task-templates`, `add-dashboard-tariff-cost-estimates`, `add-dashboard-operational-period-metrics` |
| DB-18 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-task-templates` |
| DB-19 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | — |
| DB-20 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-operational-period-metrics` |
| DB-21 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-energy-aggregates`, `add-dashboard-tariff-cost-estimates`, `add-dashboard-operational-period-metrics` |
| DB-22 | [add-private-dashboard-domain](archive/2026-10-08-add-private-dashboard-domain/proposal.md) | `add-dashboard-visual-editor`, `add-dashboard-task-templates`, `add-team-dashboard-publication` |
| DB-23 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-energy-aggregates` |
| DB-24 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | — |
| DB-25 | [add-dashboard-energy-aggregates](add-dashboard-energy-aggregates/proposal.md) | — |
| DB-26 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-energy-aggregates`, `add-dashboard-tariff-cost-estimates`, `add-dashboard-operational-period-metrics` |
| DB-27 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-energy-aggregates`, `add-dashboard-operational-period-metrics` |
| DB-28 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-visual-editor` |
| DB-29 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-visual-editor` |
| DB-30 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | `add-dashboard-widget-query-engine` |
| DB-31 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | `add-dashboard-widget-query-engine`, `add-team-dashboard-publication` |
| DB-32 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-dashboard-visual-editor` |
| DB-33 | [add-private-dashboard-domain](archive/2026-10-08-add-private-dashboard-domain/proposal.md) | `add-workspace-resource-membership` |
| DB-34 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | `add-workspace-resource-membership`, `add-dashboard-tariff-cost-estimates` |
| DB-35 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | `add-workspace-resource-membership` |
| DB-36 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | `add-workspace-resource-membership` |
| DB-37 | [add-team-dashboard-publication](add-team-dashboard-publication/proposal.md) | `add-workspace-resource-membership` |
| DB-38 | [add-private-dashboard-domain](archive/2026-10-08-add-private-dashboard-domain/proposal.md) | — |
| DB-39 | [add-private-dashboard-domain](archive/2026-10-08-add-private-dashboard-domain/proposal.md) | `add-dashboard-widget-query-engine` |
| DB-40 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | `add-private-dashboard-domain` |
| DB-41 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | — |
| DB-42 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | — |
| DB-43 | [add-dashboard-visual-editor](archive/2026-10-09-add-dashboard-visual-editor/proposal.md) | `add-private-dashboard-domain`, `add-dashboard-widget-query-engine` |

## Widget W01–W15 Coverage

| Widget | Query/domain owner | Release dependency |
| --- | --- | --- |
| W01 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W02 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W03 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W04 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W05 | [add-dashboard-energy-aggregates](add-dashboard-energy-aggregates/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W06 | [add-dashboard-energy-aggregates](add-dashboard-energy-aggregates/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W07 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W08 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W09 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W10 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W11 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Single-account matrix в query engine; workspace scope после team publication. |
| W12 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
| W13 | [add-dashboard-widget-query-engine](archive/2026-10-08-add-dashboard-widget-query-engine/proposal.md) | Template/editor используют capability только после implementation query/mathematical contract. |
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

## Dashboard concept alignment — 2026-10-09

The current product baseline is [named working screens](../specs/dashboard-working-screens/spec.md),
[card movement](../specs/dashboard-card-movement/spec.md),
[compact workspace](../specs/compact-dashboard-workspace/spec.md) and
[minimal presentation](../specs/minimal-dashboard-presentation/spec.md).
Each screen has its own widget set and responsive layout; no Overview/Analytics
screens are mandatory. Configuration is in the right drawer, left navigation
is optional, and normal evidence prose does not occupy the canvas.
Energy, tariff, operational and team extensions inherit this baseline; optional
comparisons and information disclosures do not weaken coverage or authorization.
Local synthetic demo content illustrates these layouts and is not a mandatory
seed, fabricated production evidence or acceptance of the five pending changes.
This audit updates current specs and active plans, preserves archived history and
leaves implementation task checkboxes unchanged.

The grid-editing refinement replaces hand-written card-edge dragging with
react-grid-layout, explicit resize handles and a visible Edit grid. Lucide SVG
controls and named settings sections replace symbolic primary editing controls;
keyboard movement remains in panel options. Physical browser gestures are not
independently verified with the DOM-only tooling; installed-library mouse-event
integration tests and live UI inspection are recorded in the change design.

Grid editing completed and archived as
[2026-10-09-improve-dashboard-grid-editing](archive/2026-10-09-improve-dashboard-grid-editing/proposal.md)
with four verified tasks. The original five pending changes remain active.

The [visual widget palette](archive/2026-10-09-add-dashboard-widget-palette/proposal.md)
was implemented and archived with four verified tasks. Add widget now opens
a nonmodal catalog with search and drag-to-grid previews, plus keyboard/mobile
Add buttons. Native browser dragging, configuration opening and Undo were
exercised; the original five pending changes remain active.

Panel arrangement and management actions now live in the right settings drawer
under Layout. The card ellipsis/floating form is removed; pointer grid gestures
and keyboard alternatives remain. This refinement changes presentation only and
does not complete the original five pending changes.

The panel-settings refinement was completed and archived as
[2026-10-09-move-dashboard-panel-actions-to-settings](archive/2026-10-09-move-dashboard-panel-actions-to-settings/proposal.md)
with three verified tasks; the original five pending changes remain active.

The [panel-information/navigation refinement](archive/2026-10-09-polish-dashboard-details-and-navigation/proposal.md)
was completed and archived with four verified tasks. Tile Add buttons were
removed, evidence disclosures reformatted and desktop/mobile navigation
consolidated. The original five pending changes remain active.

## Dashboard navigation alignment - 2026-10-10

[Dashboard navigation](../specs/dashboard-navigation/spec.md) and
[sidebar/catalog management](../specs/dashboard-sidebar-management/spec.md)
own the current agreed entry points. Dashboards opens /dashboards; its children
open concrete dashboards. The sidebar contains links only, while creation,
search, item management and personal menu visibility belong in the catalog.
Data/time/refresh use structured right settings; the canvas remains compact.
There is no separate My dashboards destination. The personal startup/default
route /dashboard remains supported independently of the catalog menu entry.
[Grid restoration and sizing](../specs/dashboard-grid-restoration/spec.md)
requires reversible neighbour previews, numeric per-screen grids, flexible panel
sizes and charts filling their available area. The four active dashboard
extensions inherit these contracts. Their implementation tasks remain unchecked;
this documentation audit does not deliver their pending domain capabilities.
