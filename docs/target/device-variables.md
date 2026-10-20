# Переменные устройств и преобразования телеметрии

Статус: первая версия реализована и проверена 2026-10-17 за opt-in flags.
Текущая реализация и ограничения: [current architecture](../current/architecture/device-variables.md).
Доказательства и ограничения: [verification](../current/flows/device-variable-verification.md).
План поставки и зависимости: [OpenSpec program](../../openspec/changes/README.md#device-variables-program).
Этот документ владеет общей архитектурой; change design/specs уточняют границы
этапов. Реализованные контракты находятся в `docs/current/`.

## 1. Цель и границы первой поставки

Пользователь определяет числовую переменную для своего типа устройства,
привязывает входы к полям конкретного источника, проверяет результат и включает
расчёт. Переменная сохраняется вместе с обычными измерениями и доступна для
истории, графиков, дашбордов и пороговых тревог.

Сквозной пример: `sensorTemperatureRaw=235`, преобразование `x*0.1-0.8`,
результат `chamberTemperature=22.7 °C`. Два холодильника одного типа используют
одно определение, но имеют независимые источники, значения и права доступа.
Сырое значение 235 не называется `temperatureC`: физические ограничения °C
применяются к результату, а ко входу — его собственный числовой контракт.

Первая поставка: конечные числовые значения, аффинное преобразование и
арифметическое выражение по нескольким числовым полям ОДНОГО сообщения ОДНОГО
источника. Сохранение результатов включено для активной вычисляемой переменной.
Входные зависимости также должны сохраняться для объяснения результата.
Формулы не имеют сетевых вызовов, доступа к БД, часам или случайности.

Не входят: произвольный Java/Python/JS/SQL, история внутри формулы, last-value
join, зависимости между вычисляемыми переменными, межустройственные расчёты,
потоковые окна, перерасчёт старой истории и автоматическое подключение к ML.
Эти ограничения проверяются сервером. Они не являются незавершёнными задачами
первой поставки. Агрегация для графика за период остаётся read-side операцией.

## 2. Существующая основа и пробелы

- `device-service` уже владеет типами, источниками и `TelemetryTemplates`.
  Сейчас type assignment применяется к существующим источникам; новая модель
  должна явно поддержать последующее создание устройств и источников.
- `TelemetryFieldPolicy` фильтрует данные до публикации. Новая формула не сможет
  использовать отброшенное поле; политики хранения и привязки проверяются вместе.
- Modbus уже умеет `value*scale+offset`. Это протокольное декодирование,
  пользовательская формула получает его результат; UI показывает эту границу.
- `telemetry.raw.v1` — результат адаптера/политики, не оригинальный пакет устройства.
- `MetricCatalog`, `IngestionMetricCatalog`, `TelemetryEventProcessor` и клиенты
  аналитики используют фиксированные имена. Сохранённые `configuredFields` сами
  по себе ещё не обеспечивают полноценный числовой ряд и работу тревог.
- Telemetry сохраняет результаты и публикует validated через outbox. Analytics
  исполняет пользовательские правила, alert-service владеет определениями правил
  и lifecycle тревог. Новая возможность сохраняет эту границу.

См. [boundaries](../current/architecture/02-service-boundaries.md),
[flow](../current/architecture/03-data-flow.md),
[consistency](../current/architecture/10-consistency-contracts.md).

## 3. Владение и модульные границы

| Владелец | Ответственность |
| --- | --- |
| device-service | Definition, immutable revision, source binding, type defaults, publication audit, activation and runtime bundle outbox |
| ingestion-service | Local input contract, adapter decoding, authorized acceptance, input retention, pinning processing context; no formula execution |
| telemetry-service | Durable bundle projection, evaluator, measured/derived series catalog projection, measurements, provenance, evaluation failures, preview, history and query aggregation |
| analytics-service | Source-bound variable rule evaluation, quality/maintenance policy, anomaly evidence and outbox; no copy of formula evaluator |
| alert-service | Rule CRUD/version/cooldown/dedup and alert lifecycle/notifications; no direct telemetry database access |
| dashboard-service | Typed widget intent and presentation; authorized bounded reads from telemetry, no business calculation engine |

Variables are a focused domain module in device and telemetry, not new catch-all
configuration services. Controllers/consumers delegate to application use cases;
pure expression evaluation depends only on immutable inputs. Persistence,
projection, HTTP, Kafka and outbox are ports/adapters. Shared common-events holds
wire DTOs, not formula business logic. Preview and production use the same
telemetry-owned evaluator and validation library within that service.

No new deployable computation service or AWS dependency is required. Extraction
is justified later by measured CPU/backlog/isolation needs; stable contracts allow
an executor to move without transferring telemetry database ownership.

## 4. Definition, revision, binding and identity

- `VariableDefinition`: UUID, accountId, deviceType reference, immutable key,
  editable label, `origin=OBSERVED|DERIVED`, creation/audit fields and archive state.
  A type's global code does not grant cross-account ownership of definitions.
- `VariableRevision`: variableId, revision, expressionLanguageVersion, input aliases
  and numeric/unit contracts, expression AST, output unit and optional bounds,
  digest, lifecycle `DRAFT|PUBLISHED`. Published revisions are immutable.
- `TypeVariableDefault`: account/type/variableId/publishedRevision. New matching
  devices inherit visibility; new eligible sources receive a binding only when
  an explicit source role and all input mappings resolve unambiguously.
- `SourceVariableBinding`: accountId, deviceId, dataSourceId UUID, variableId,
  pinned revision, input alias-to-field-path mapping and enabled state. Never
  select an arbitrary source when multiple sources match. Unresolved binding
  is visible as `UNBOUND` and performs no computation.
- `SourceProcessingBundle`: immutable complete snapshot for one dataSourceId,
  monotonic generation, schema version, digest, active revisions, input retention
  contract, mappings and referenced observed-field identities. It has no secrets.

Binding/assignment writes atomically update the source input policy and bundle
generation in device-service. Assigning a template that removes a required input
returns conflict with the dependent variables; no silent widening of retention.
Type-wide propagation is a bounded resumable job with per-source outcomes, not a
distributed transaction over all devices. Existing bindings remain pinned until
an explicit rollout; changing the default affects later bindings. Label edits
must not mutate measurement identity or historical unit interpretation.

Built-ins keep legacy names. Custom numeric fields and derived variables use
stable UUID variableId, with a reserved internal storage token such as
`var_<uuid>`; users cannot spoof that namespace or overwrite a built-in. Human
keys and labels are not storage identity. APIs use variableId and source UUID
for new series. Both observed custom inputs and derived outputs have catalogue
entries; no separate registry service owns another definition copy.

Unit/output type/key changes require a new definition; formula/coefficients or
input contract changes create a new revision. Archive blocks new bindings and
disables future computation through a new bundle; history and used revisions
remain readable. Deletion never removes revisions referenced by measurements.

## 5. Configuration transport and acceptance version

New target topic `source.processing-config.v1`, compacted, key=dataSourceId.
Producer=device-service outbox; consumers=ingestion and telemetry. Snapshot has
eventId, account/device/source identities, generation, bundle digest/content,
enabled state, publishedAt and usual envelope fields. No source credentials or
token hashes are copied from `source.runtime-config.v1` to this topic.

`source.runtime-config.v1` retains its current consumers and source ownership.
Additive optional processing generation/digest references let ingestion combine
source auth and processing projections. It accepts configured input only when
the generations are compatible; propagation lag returns a retryable unavailable
state, never fallback to a different formula. Unconfigured legacy sources behave
as before. Status/revocation uses existing source security semantics.

Each replica must have complete read-model coverage (replica-specific consumer
group or shared durable projection plus invalidated cache). A worker group that
gives each ingestion replica only a subset of sources is insufficient. Telemetry
persists immutable historical bundles in its database, not just compacted latest.
Bootstrap uses authenticated paginated reconciliation from device-service, which
retains historical bundle generations; lower/duplicate events cannot regress state.

For configured sources add an optional top-level `processingContext` to
`TelemetryReceivedEvent`: dataSourceId, generation, digest and input schema
version. It is server-assigned, outside user metrics, and cannot be client-spoofed.
Existing raw topic/key and unconfigured payload semantics remain compatible.
All readers must tolerate new fields before producer activation; add serialization
fixtures and upgrade checks. A breaking wire change requires a versioned contract,
not silent alteration of v1 semantics.

The applied formula is pinned at acceptance by ingestion, not chosen later from
the latest configuration or inferred from event time. Delayed/replayed events use
their pinned bundle even after edits. Missing pinned bundle is durably parked with
bounded retries/reconciliation and eventual DLQ, not acknowledged and discarded
or recomputed using latest. Parking stores original eventId/context for dedup.
Finite compacted-topic retention is not the only replay source.

## 6. Processing, validation and failure semantics

1. Adapter emits canonical payload, including configured numeric input fields.
2. Ingestion resolves authorization and a compatible bundle locally, retains
   required declared inputs, validates finite numeric types/input bounds and
   envelope limits. Legacy canonical field checks remain enforced.
3. Ingestion publishes raw with pinned context; dedup remains based on input
   identity, not on whichever formula revision happens to be current.
4. Telemetry resolves exactly that immutable bundle and validates the trusted
   context and source/account association. Observed configured numeric inputs
   become first-class measurements even without GPS or built-in numeric fields.
5. Evaluator calculates each derived variable using only the same accepted
   message. `+ - * /`, parentheses, finite literals and declared aliases are
   allowed; no loops, calls, reflection, assignments, recursion or computed inputs.
6. Per-variable output is checked for finite value, declared unit and bounds.
   Missing input, zero divisor, invalid output and exhausted limits produce an
   explicit failure record with no fabricated value. Valid siblings still persist.
7. Measurements, lineage, failure outcomes, processed marker and outgoing events
   commit atomically. Infrastructure failure rolls back/retries; data errors are
   recorded terminal outcomes. Retry cannot duplicate output rows or events.

Initial budgets: at most 32 active derived variables/source, 8 inputs/variable,
64 AST nodes/variable, depth 12, 2048 expression characters and 4096 evaluated
nodes/message. Whole bundle/input/output payload must also fit existing configured
message limits. Validate these before publish; runtime defensive budgets remain.
Benchmarks in acceptance may lower/tune budgets with documented evidence. IEEE
finite arithmetic is deterministic for the pinned evaluator version; no implicit
rounding for storage. Display precision is presentation metadata.

Limits/units are explicit; no conversion inferred from a label. V1 supports
affine unit conversion and declared-unit arithmetic with dimension validation;
unsupported unit combinations reject publication. Preview returns intermediate
values and diagnostics with the same unit rules.

## 7. Storage, catalogue and accepted measurement events

Telemetry owns observed and computed numeric rows in the existing measurement
storage/read path, extended by variableId/revision and provenance references.
Use a nullable/additive migration or lineage companion table according to the
existing database constraints, but never create an inaccessible derived-only
history API. Proposed per-output uniqueness is
`(accountId, deviceId, dataSourceId, sourceEventId, variableId, revision)`.
Existing device/source/timestamp dedup and replay behavior must be reconciled
explicitly in migrations/tests, not bypassed with random IDs.

Lineage includes sourceEventId/readingId, bundle generation/digest, revision,
evaluator version, input identities/values, recordedAt and receivedAt. Store input
evidence with retention aligned to derived rows or preserve a bounded immutable
input snapshot in lineage. Formula and output unit at calculation time survive
later edits. Latest orders by recordedAt with a deterministic tie break; late
data does not overwrite newer evidence. Index source/variable/time reads; verify
PostgreSQL/Timescale and H2 migrations.

New target topic `telemetry.measurements.accepted.v1`, key=deviceId,
producer=telemetry outbox. It carries reading/event identity, account/device/source,
recordedAt/receivedAt, processing context and bounded typed observed/derived
measurements with variableId, revision, unit, quality, lineage reference and
eligibility `LIVE|BACKFILL`. Event IDs/outbox keys are stable on retries.

Existing `telemetry.validated.v1` remains for current built-in analytics. New
variable-rule evaluation consumes accepted measurement events in a separate
idempotent path; legacy metrics must not trigger duplicate recalculation or
duplicate anomaly publication. Derived outputs are never sent back to raw input.
Backfill events are stored but cannot create live alerts. Adding custom variables
does not change ML feature schemas, built-in risk or energy selection implicitly.

## 8. API and authorization targets

Public paths are unversioned as elsewhere; schema/revision fields version data.
Exact DTOs and route conflict tests are deliverables of the owning changes.

| Owner | Target endpoint | Contract |
| --- | --- | --- |
| device | `GET/POST /device-types/{code}/variables` | Account-scoped catalogue / create definition and draft |
| device | `GET /device-types/{code}/variables/{id}` | Definition, revisions and rollout summary |
| device | `POST /device-types/{code}/variables/{id}/revisions` | Optimistic expectedRevision, creates draft |
| device | `POST /device-types/{code}/variables/{id}/publish` | Validate, immutable revision and explicit default/rollout intent |
| device | `POST /device-types/{code}/variables/{id}/archive` | Preserve history, disable via resumable rollout |
| device | `GET/PUT /devices/{id}/data-sources/{sourceId}/variable-bindings` | Authorized version-checked source bindings/input retention |
| device internal | `GET /internal/source-processing-bundles` | Authenticated cursor reconciliation, optional source/generation filter |
| telemetry | `POST /telemetry/variables/preview` | Authorized proposed AST + bounded explicit inputs; no writes or live events |
| telemetry | `GET /telemetry/devices/{id}/variables` | Authorized measured+computed descriptors and local activation status |
| telemetry | Existing readings/latest/chart/export queries | Add variableId/source UUID selector; preserve legacy metric selector |

Ambiguous legacy/custom selectors return validation errors. Definition write,
publish, bind, preview/read and rule management use current account/workspace
resource capabilities; source/type code knowledge is never authority. Type
defaults are account-scoped. Batch and internal reads validate all source/device
targets; cache keys include authorized scope, source, variable, revision policy,
range and resolution. Authorization is renewed before cached delivery.

API errors distinguish invalid expression/unit (400), inaccessible resource
(existing non-disclosing policy), optimistic conflict/input-policy conflict (409),
propagation lag (503 + retry guidance). Publication is strongly persisted in
device, activation is eventual: UI shows desired generation versus local applied
generation with `UNBOUND|PROPAGATING|ACTIVE|ERROR|DISABLED` evidence.

## 9. Charts and query-time calculations

Existing telemetry query owner performs bounded filtering and SQL aggregation.
Stored variable rows use the same history/latest/export interfaces and widget
selectors. Units come from descriptors, never frontend key-name guessing.
For charts support existing reducers and explicit bucket width/resolution, sample
count, gaps and revision boundaries. A change of formula revision is visible;
default aggregation splits revision segments rather than mixing them silently.
Sample-average is named as such; no claim of time-weighted average.

A graph-only bucket result is returned or cached with TTL, not automatically
registered/materialized as a new variable. Alert evaluation never uses decimated
chart points. Report scheduling/general formula queries are outside this program.

## 10. Variable threshold rules and lifecycle

Alert-service extends existing rule definitions with variableId, source binding,
operator, threshold in the variable's unit and rule version. V1 supports
ABOVE_THRESHOLD and BELOW_THRESHOLD for live numeric observations. Source is
explicit; no mixing two sensors in the same device. Conditions involving time
duration, cross-source joins or arbitrary expressions remain separate future work.

Analytics consumes accepted measurement events, loads the rule snapshot using
the existing internal rule boundary (bounded local cache and explicit unavailable
retry, never an empty-rule success on dependency failure), and evaluates the
exact eligible measurement. Dry run uses bounded full-resolution telemetry reads
with the same predicate, returning insufficient/invalid evidence distinctly.
No piggybacking on sampled chart queries or ML recalc jobs is permitted.

Matched evidence is persisted with input identity, variable/revision, rule/version,
source, value/unit and digest. Analytics outbox emits existing anomaly contract
with additive optional structured variable evidence (compatibility-tested),
consumed by alert-service for lifecycle and notification. Idempotency includes
source measurement + rule version; existing cooldown/dedup remains a second
business layer. Different sources/rules cannot collapse accidentally.

LIVE eligibility is necessary but not sufficient for a current alert. Variable
rules have explicit maxLiveAgeSeconds (default 600, configurable within a bounded
server policy); timestamps older than that at evaluation are recorded as stale,
not matched. Out-of-order replay cannot create a new current alert from stale
history. This threshold is a product freshness policy, not an ingestion rejection
or promised processing latency. Infrastructure retries preserve original time.

Maintenance exclusions/suppression retain current owners and semantics. Invalid,
missing, unbound, backfill and disabled-scope evidence never becomes numeric zero
or a live match. Rule references bind stable variableId; new formula revision
preserves threshold/unit and is shown in evidence. Revoked resource access must
prevent new evaluation/delivery under current account policies. V1 does not
introduce automatic resolve-on-recovery; existing lifecycle remains explicit.

## 11. Deployment, verification and rollback

Rollout: additive schemas/DTO readers first → definition/projection paths →
input support/evaluator/read paths → variable rule consumer → UI → explicit
per-source activation. Separate flags for configuration activation, evaluation
and variable rule consumption default off until prerequisites are deployed.
Once enabled, disabling evaluator parks configured work durably and reports
unavailable; it never falls back to unconfigured interpretation.

Pin active definitions during partial deployment. Rollback disables new bindings
and live variable rules first, retains compatible readers/history and drains or
parks in-flight events. Old binaries cannot be restored blindly after new
configured messages exist. Disable/archive has per-source propagation evidence.

Observe bundle projection lag, parked event age/count, terminal evaluation failures
by bounded reason, compute latency/operation counts, output amplification, outbox
lag and rule evaluation/dedup. Never use variable/source IDs as unbounded metric
labels. Structured logs carry identity and correlation without secrets.

Acceptance includes two devices of one type, independent sources, custom-only
numeric messages, 235→22.7, second formula revision, delayed old bundle replay,
replica/restart/reconciliation, missing input/division zero, policy conflicts,
history/latest/export/chart, thresholds and exactly one lifecycle result on replay,
maintenance, backfill, denied access and legacy temperature/GPS/energy regression.
Measure sustained input/output amplification and query limits using a declared
fixture; do not claim production capacity from unit tests.

Before implementation archive, publish actual API/event/migration/operations
contracts in `docs/current/`, add an ADR for adopted ownership/version decisions,
and reconcile this target document with delivered behavior. No AGENTS/skill change
is necessary solely for this planning program.
