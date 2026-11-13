# Настраиваемая платформа данных, геообъектов, правил и AI

Статус: **переработанная целевая архитектура**, 2026-11-03.
Пользователь остановил реализацию специализированного transit-сценария и уточнил:
платформа должна собирать множество бизнес-сценариев из общих возможностей.
Этот документ и [новая программа](../../openspec/changes/README.md#configurable-operations-program)
заменяют прежнюю T1–T7 очередь как направление реализации.
2026-11-03 пользователь явно возобновил реализацию. Начат G0/M0:
[common-vcl static front end](../current/architecture/vcl-compiler-front-end.md).
Native data owner API, ручной /data workbench, source schema/grant adapters и первый
opt-in accepted-input journal реализованы отдельными prerequisite-срезами.
Temporal runtime, полный owner/bundle path и UI/AI integration ещё не завершены;
[текущий статус](../../openspec/changes/README.md#configurable-operations-execution-status)
отделяет реализованные срезы от readiness и полной приёмки.

Применимость к исходному автобусному запросу, обнаруженные integration gaps и
BUS01–BUS10: [аудит готовности сценария](configurable-operations-bus-readiness.md).

## Нормативные контракты законченного проекта

Обзор ниже задаёт продуктовые границы. Точные обязательные решения находятся в:

- [VCL/1: типы, методы, сигнатуры и семантика](configurable-operations-language.md);
- [формальной JSON-грамматике](configurable-operations-language.schema.json) и
  [документах-примерах](configurable-operations-language.examples.json);
- [физическом хранении, внешних БД и записи](configurable-operations-storage.md);
- [строгих импортах и синхронизации](configurable-operations-imports.md);
- [owner API, review и зависимых шагах](configurable-operations-control.md);
- [инвариантах и A01–A66](configurable-operations-acceptance.md).
- [аудите всех активных changes](configurable-operations-spec-alignment.json).

Эти контракты заменяют прежние tentative варианты layout/interpreter и порядок
«сначала весь конструктор, потом AI». Законченная спецификация не является
утверждением о реализованной платформе. Для каждого источника явно объявляются
поддерживаемые методы чтения, snapshot/history/CDC и безопасной записи.

## 1. Продуктовая модель

Компания определяет свои таблицы, поля, связи, места на карте, источники
наблюдений, показатели и правила. Она может настроить их через UI или объяснить
задачу AI. Результат хранится как проверенная версия конфигурации и выполняется
сервером независимо от доступности AI.

Примеры конфигураций одной платформы:

| Сценарий | Таблицы/связи | Наблюдения | Правило |
| --- | --- | --- | --- |
| Обслуживание остановок | Точки, план посещений, обязательность, транспорт | Позиция, дверь, скорость | Выход из зоны без требуемого события внутри посещения |
| Переработки автопарка | Сотрудники, назначения на машины, смены, нормы | Подтверждённые интервалы работы/перерыва | Объединённое рабочее время превышает настроенный лимит |
| Задержка доставки | Заказы, назначения, адреса/геозоны, окна доставки | Вход/выход, подтверждение вручения | Не выполнено ожидаемое действие до дедлайна |
| Холодильник | Оборудование, товары, нормы хранения | Температура, дверь, исправность датчика | Превышение температуры заданное время при достаточном покрытии |

Новая остановка, склад, сотрудник, тип актива или порог — конфигурация,
а не новая Java entity/controller/rule branch. Новый протокол или новый
математический оператор может требовать разработки и квалификации.
Гибкость ограничивается опубликованными capabilities, не обещанием произвольной
программы, написанной моделью.

## 2. Что переиспользуем

Текущие механизмы сохраняются:

- device/source/type ownership, workspace grants и signed identity;
- observed/derived variables, их UUID, единицы, immutable processing bundles;
- operating_schedule_revisions и operational_fact_revisions;
- существующие circles и общая карта как UI-основа;
- alert lifecycle, suppression, dedup и notification ports;
- dashboard compiler, owner manifests и общий preview/renderer;
- Spring AI, SkillRegistry, durable plans, вопросы, receipts и AttemptLedger.

Сейчас арифметические переменные не имеют history/join/window state;
frontend ENTER/EXIT по записанной истории не является серверным evaluator.
Новые temporal operators не добавляются скрытно в числовой AST v1 и не
переинтерпретируют уже сохранённые формулы/operational facts.
Существующие бизнес-модули получают typed projection/binding, а не вторую
независимую копию источника истины.

## 3. Общие возможности и владельцы

| Возможность | Что настраивается | Ответственный |
| --- | --- | --- |
| Typed data collections | Schema, rows, refs, effective periods, imports | device-service: отдельный configurable-data module |
| Customer databases | Connection, schema mapping, bounded reads, verified writes | Focused external-data adapters; source DB remains authoritative |
| Spatial features | Point, circle, bounded polygon, feature set | device-service: spatial module |
| Source mapping | Поля, units, event kinds, entity identity, quality | Existing source/ingestion owners |
| Event observations | Actual events, event time, provenance, coverage | telemetry-service |
| Declarative derivations | Lookup, sessions, intervals, windows, aggregates | analytics-service; existing per-message variables remain telemetry-owned |
| Declarative rules | Scope, predicate, absence deadline, alert policy | alert-service definitions, analytics execution |
| Workbench and dashboards | Tables/map/rule editor, history, actual evidence | Frontend + existing dashboard owner |
| AI authoring | Discover, explain, propose, test, review, apply | Existing ai-agent-service |
| Provider packages | HSL/GTFS, company CSV/API, later other adapters | Focused adapters and versioned mappings |

No new catch-all service, parallel database owner, agent engine or second ledger.
Cross-service access stays through authorized APIs/events, not DB joins across
services. Read-side projections carry immutable references and access scope.

## 4. Пользовательские таблицы и отношения

CollectionDefinition: stable id/key, account/workspace scope, display metadata,
schemaRevision, digest, lifecycle and field capability metadata.
Schema types: bounded string, decimal/integer with units, boolean, enum,
date, instant, duration, GeoPoint, GeoFeatureRef, EntityRef and CollectionRowRef.
Relations declare target collection/resource, cardinality, inverse display and
required/optional status. Labels and user keys are never physical storage IDs.

Row: stable rowId, collectionId, CAS recordVersion, schemaRevision,
values, provenance, recordedAt, actor and optional validFrom/validTo.
Immutable payload history is an explicitly enabled capability; stable snapshots
or history are required for reproducible historical rules.
Temporal assignments (employee ↔ vehicle, order ↔ route, asset ↔ owner role)
are effective-dated relations. Overlap policy and expected cardinality are explicit.
A missing or ambiguous assignment is UNKNOWN; the engine cannot pick first match.

The existing Device remains the canonical physical asset when applicable.
A user collection may reference Device; it cannot redefine ownership, spoof
accountId or impersonate an existing device. The same applies to locations,
sources, variables, schedules and operational facts. New business concepts
(e.g. Customer or Delivery) need no new core service, but retain scoped identities.

Native storage is a server-provisioned dense typed SQL table per collection.
Metadata/schema tables are shared; scalar values are not EAV. A normal list/get
does not join scalar/relation/history tables. Inline refs, explicitly selected
indexes, optional history and explicit temporal relations avoid charging every
simple dataset for unused features. User/AI SQL/DDL is forbidden; a trusted
metadata compiler provisions physical columns/indexes through reviewed jobs.
External data stays in the customer DB; local projections copy only approved
fields/rows for explicit runtime needs. PostgreSQL16 capacity/DDL tests are required;
H2 does not establish performance. Full decisions are in the storage contract.

Import creates a mapping preview, schema/type/reference diagnostics and bounded
commit with receipts. File/web text cannot create fields or relationships without
the reviewed schema change. Bulk writes have resumable chunks, stable per-row
keys and a persisted import snapshot. Remote API source has freshness/errors;
failure is not an empty valid table.

Schema changes are classified: additive, constrained migration, breaking.
Publish computes affected forms/rules/dashboards/mappings and a migration preview.
A published revision remains immutable; no changing numeric hours into seconds
or relation targets under the same interpretation. Dependent rules stay pinned,
or an explicit migration revalidates all affected resources.
Deletion is guarded by references/retention, with archive/tombstone semantics.

## 5. Внутренний язык — три связанных контракта

Нужен не язык «автобусов», а versioned declarative representation:

1. **Data schema:** какие сущности, поля, единицы и связи существуют.
   JSON Schema covers structural validation; domain checks add typed refs,
   units, permissions and migration invariants.
2. **Query/derivation plan:** откуда брать данные, как связать их и что вычислить.
   Bounded typed graph with field/resource IDs, explicit temporal semantics.
3. **Rule plan:** когда результат является условием тревоги, когда неизвестен,
   какие действия разрешены и когда правило активно.

UI, AI и import tooling генерируют один canonical representation.
Model provider wire schema derives from server capabilities; it is not another
runtime language. Raw natural-language prompts, SQL and generated scripts
never become the stored executable policy.
Textual expressions may be an optional editor compiled to the same typed AST;
the AST is canonical and has a language version/evaluator version.

Registered primitive families of the complete VCL contract:

| Group | Operators and essential semantics |
| --- | --- |
| Inputs | Authorized event/measurement stream, explicit collection revision/as-of lookup |
| Relations | Equality ref lookup and effective-dated assignment; bounded fan-out |
| Scalar | Typed compare, boolean logic, bounded arithmetic/unit conversion |
| Space | Distance, inside/outside, observed enter/exit, uncertainty/debounce |
| Sessions | Entity-keyed visit/session; explicit opening, closing and timeout |
| Time | Event-time windows, intervals, deadlines, allowed lateness and calendars |
| Aggregates | Count, sum, min/max, union-duration of nonoverlapping effective intervals |
| Sequence | Observed action within session; absence only after closed covered window |
| Quality | Missing/ambiguous/stale/left-censored/coverage checks → UNKNOWN |
| Output | Derived fact/metric and typed alert proposal with evidence |

Example logical plan, **illustrative target notation, not an implemented API**:

```text
input observations(entity = vehicle, source = selectedSource)
lookup planned_visits using (vehicle, trip, occurrence), effective_at = event.time
session visit = spatial_visit(vehicle, planned_visit.zone, exit_debounce = 3s)
witness service = observed(door.open AND stationary, within = visit)
evaluate after visit.exit + 10s:
    planned_visit.required AND NOT service AND coverage(visit).sufficient
emit possible_missed_service once_per (vehicle, trip, occurrence)
```

The generic plan has no HSL-specific event in its evaluator. A package maps
provider data to typed fields and provides suggested collection schemas.

Overtime uses the same infrastructure, a different graph:

```text
input confirmed_work_intervals
lookup employee_assignment effective_at = interval.time
split intervals at assignment changes and employee_calendar boundaries
exclude explicitly classified unpaid_break_intervals
duration = union_duration(work_intervals, grouped_by = employee, local_workday)
compare duration to approved_limit(employee, effective_at = workday)
emit work_duration_limit_exceeded once_per (employee, workday, policy)
```

Vehicle movement, ignition and employee work are different facts. AI asks whether
the desired metric is engine running, vehicle use, employee work or departure
from schedule, then asks for the authoritative source and break policy.
Without employee assignment or valid work evidence it can calculate a labeled
vehicle-utilization proxy only if explicitly chosen; it cannot call that payroll
or confirmed employee overtime. Limits are company-supplied settings, not
invented legal norms. Corrections, overlapping intervals, two vehicles per
employee, midnight, DST and multi-day sessions are first-class cases.

## 6. Stateful execution, joins and versioning

A compiled plan declares event/source schema versions, field/ref types,
collection/policy bindings, key, cardinality, window, retained state, late policy,
resource budgets, required access and supported operator versions.
Publication fails on unsupported operators, type/unit mismatch, cycle,
unbounded lookup/window/fan-out or unresolved fields.
Declared limits: graph ≤64 nodes, relation depth ≤2,
bounded 31-day lookback, per-node cost and per-account state quotas.
These are compiler bounds; runtime capacity still requires measured evidence.

Configuration and mutable operational rows have different binding semantics:
- Schema, policy constants and expression/operator versions are pinned.
- Expected plans/assignments use an explicit effective-at-event-time lookup
  and record exact row revisions used.
- Whole-table immutable snapshots are supported for schedules/reference imports.
- Correction/backfill is an explicit reevaluation mode, never a hidden switch
  to latest assignments when replaying old events.

A join waits for required reference projection watermarks or records UNKNOWN.
No network/SQL lookup per incoming event; projections and bounded indexes support
stream execution. Mixed joins across clocks must have explicit max skew/freshness.
Null is not false or zero; absence requires coverage and finalization.
Stalled stream uses durable timers; wall-clock silence is not an observed exit.

Capture policy is selected by a published capability: latest sampling for maps,
event-preserving capture for transitions, or intervals for work sessions.
Capture omissions, overflow and disconnected periods propagate as quality.
A more expensive model cannot recover a door event that was discarded.

Runtime state/checkpoints, inbox/outbox, evaluator readiness ACK and activation
epoch survive restart. Rule activation and projection lag are visible.
One alert identity belongs to a logical subject/window/occurrence, independent
of replay or a new rule revision; corrections append, never erase the audit.
Dry-run/replay defaults to no delivery. Effects allowlisted initially:
create/update alert and existing notification adapters; arbitrary side-effect
scripts and automatic destructive actions are outside the initial language.

## 7. Карта как общий редактор пространственных объектов

Пользователь может выбрать существующее место, поставить точку кликом,
найти адрес через approved geocoder, задать круг, нарисовать bounded polygon,
выделить группу объектов и импортировать координаты из таблицы.
Backend declares geometry capabilities, bounds and supported CRS conversion.
GeoJSON storage uses WGS84 longitude/latitude order; UI field labels remove
ambiguity. No silently stored screen pixels or approximate guessed points.

Every feature has ID/revision, geometry, name, scope and optional links to rows.
A single point/zone can be reused by depots, stops, warehouses, deliveries and
rules. Selection on map and selection in a table share the same IDs.
Preview shows coverage/overlap, true metres, uncertainty and exact affected rows.
Clicking two nearby points produces a choice; map proximity is not semantic identity.

Draw/edit/import prepare a diff and validate before saving; optimistic concurrency,
idempotency, references and permissions match table operations.
Changing geometry does not silently mutate active plans pinned to old revisions.
Legacy owner/device circles stay compatible and import explicitly.
First shipped shape may be circle; polygon capability is advertised only after
its geometry validation, editor and runtime are implemented.

## 8. AI управляет общими возможностями

AI gets a compact authorized capability catalogue plus relevant table schemas,
field descriptions/units, relationship metadata, map selections, available
observations and current accepted goals. It can discover fields progressively,
query scoped samples and request a dry-run. It cannot infer unsupported capabilities
from a friendly description or bypass access with a generated table name.

General skills, not one intent per business scenario:
discover_capabilities, inspect_schema, query_records, propose_schema,
propose_records/import_mapping, propose_relations, propose_geofeatures,
propose_derivation, propose_rule, test_configuration,
prepare_dashboard, review_changes, apply_review and explain_evidence.
Application owns exact IDs/contracts; names in this document are proposed intent
roles, not claims about currently registered tools.

Example: «У компании 40 машин, предупреждай о переработке сотрудников».
AI finds existing collections/sources, clarifies subject/work definition and
time policy, proposes missing tables/relations, then compiles the rule and shows
sample findings. It does not begin by building a hardcoded OVERTIME handler.
For a HSL request it selects the provider package and uses the same table/map/rule
operations. Research supplies bounded evidence and candidate mappings, not
permission to install a connector or execute downloaded code.

Coordinator may delegate schema analysis, read-only research, rule proposal and
independent review. Nested consultations use a bounded server-scheduled task tree,
not recursive ChatClient callbacks, independent budgets or direct writes.
Exact requirements/consultation/metric/RAG contracts are in the
[AI collaboration specification](ai-assistant-collaboration-and-retrieval.md).
PINNED semantics are retained; opt-in qualified AUTO assigns models per skill/phase.
All calls reserve the existing shared ledger and record actual/unknown usage/price.
Stronger models are justified by measured complete-task quality; deterministic
lookup/geometric/interval calculations do not consume LLM calls.

Before mutation AI shows a human-readable diff: collections/fields/rows/points,
rule semantics, selected resources, side effects, cost and pending unknowns.
Question/reference provenance and user-only facts remain inspectable.
Schemas and records created earlier in a compound review use reviewed local
references resolved only to successful receipts; material changes invalidate consent.
Publish/activate requires explicit reviewed authorization. Exact bundle may be
confirmed once if every mutation and fan-out is enumerated; partial failure
retains completed receipts and resumes without duplicate resources.

AI intelligence is measured by correct cross-domain composition, targeted
clarifications and exact persistence, not by number of agents or by valid JSON.
Tests include novel schemas/field names, rearranged relationships, third-domain
held-out tasks, two-account resources and incorrect but schema-valid plans.

## 9. Reusable solution packages

SolutionPackage contains versioned schema templates, source mappings, default
views/widgets, rule graph templates, documentation and acceptance fixtures.
Installation prepares ordinary resources through the same review/API; no privileged
path. Updating a package creates a migration diff and preserves existing pins.

HSL package: provider adapter + route/stop/occurrence collections + mapping
door/position events + visit rule template. Trip/direction/service-day mapping
belongs in the adapter/template, not platform-wide core conditionals.
Fleet work package: employee/vehicle assignments, approved work intervals,
schedules/breaks/limits, duration graph and evidence views.
Cold-chain package: inventory/asset refs, temperature source contracts and duration
threshold rule. Packages may be authored later; generic capabilities come first.

Source connectors still require vetted executable adapters for new protocols.
CSV/JSON import and configured existing API mapping cover many data sources.
Research can identify an unsupported API and propose integration work; it cannot
turn arbitrary internet content into a trusted production data source.

## 10. Module ownership and complete acceptance

G0 owns VCL grammar/type/compiler contracts; G1 owns datasets/native storage;
GX owns customer DB adapters; G2 geometry; G3 observations; G4 runtime rules;
G5 packages/import mappings; G6 workbench; G7 AI; G8 qualification.
These are ownership boundaries, not a waterfall that postpones AI.

Delivery milestones M0–M4 and the complete A01–A66 acceptance contract are in
[acceptance](configurable-operations-acceptance.md). UI, AI, strict contracts and
owner receipts participate in each useful vertical slice. Completion of one
slice is not completion of the declared platform.

Implementation remains stopped until explicit resume. On resume begin with G0
contract/compiler checks and G1/GX dataset access, integrating G6/G7 immediately.
Do not revive specialized transit T1. HSL, fleet work and a third domain must
compose the same capabilities; a genuinely new protocol/operator requires an
explicit capability extension, not a hidden business-name evaluator branch.

## 11. External design references

- [JSON Schema](https://json-schema.org/understanding-json-schema/reference):
  schema structure/type validation; business identity and access remain server-owned.
- [CEL](https://cel.dev/): comparison reference for bounded expressions; the chosen
  contract is VCL typed AST/pure kernels, with no alternate CEL/SQL interpreter.
- [Agent composition](https://www.anthropic.com/research/building-effective-agents):
  small composable workflows, routing and bounded coordinator/worker pattern.
  Existing Spring AI boundary remains; no framework replacement is proposed.

