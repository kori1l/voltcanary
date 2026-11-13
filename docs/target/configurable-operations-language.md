# VCL/1 — внутренний язык VoltCanary

Статус: **целевая спецификация языка**, 2026-11-03; реализация возобновлена с G0/M0.
Текущий [Java parser/registry/static compiler](../current/architecture/vcl-compiler-front-end.md)
проверен отдельно; scalar runtime включает WGS84 distance и circle membership,
есть immutable bundle infrastructure; owner integration и UI/AI wiring ещё не готовы.
Этот контракт уточняет [архитектуру](configurable-operations-platform.md).
Хранение: [storage и adapters](configurable-operations-storage.md).
Импорты: [контракт](configurable-operations-imports.md).
Формальная грамматика: [JSON Schema](configurable-operations-language.schema.json);
[документы-примеры](configurable-operations-language.examples.json).
JSON Schema проверяет форму; ниже определены обязательные статические и
динамические проверки. Соответствие только JSON Schema не означает valid rule.

Уточнение implementer review: specification revision 2. VCL/1 ещё не был
реализован/сохранён runtime, поэтому ошибочные draft-формы исправляются здесь;
это не разрешение менять существующие production контракты без migration.

## 1. Что является языком

Implementation ownership: one pure Java library common-vcl owns wire types,
strict parsing/canonicalization, scalar type/unit checks and typed graph IR.
It has no Spring/HTTP/Kafka/JDBC/network access. Existing numeric AST v1 is not
silently replaced. Method contracts are registered once and generate schema/
capability metadata for UI and AI. Resource authorization/loading belongs to
owner application ports; SQL pushdown belongs to data adapters; temporal state
and execution kernels belong to analytics-service. Frontend does not implement
a second rule evaluator. No new deployable compiler/interpreter service.

VCL/1 — типизированные декларации коллекций, запросов, импортов и графов правил.
Транспорт — строгий UTF-8 JSON: envelope {language:"VCL/1",kind,spec}.
kind = COLLECTION | QUERY | IMPORT | RULE | CONNECTION | MUTATION.
Неизвестные поля/методы запрещены.
Нет JavaScript/Python/SQL, eval, reflection, циклов, рекурсии, сетевых функций
и пользовательских исполняемых расширений. Изменение каталога операторов —
версионированная поставка платформы и её тестов.

UI и AI создают один VCL. Человекочитаемый редактор — только представление того
же документа. Исходный prompt, summary модели или найденный SQL не исполняются.
Ни data schema, ни VCL не содержат passwords, API keys, access/refresh tokens.

Все resource/field IDs — UUID. Отображаемые названия не участвуют в binding.
Имена узлов — технические labels внутри одного документа. Ссылки на ресурсы
разрешаются только через owner metadata/API; локальные ссылки review разрешаются
в UUID по receipts до компиляции исполняемого bundle.

Canonical digest: SHA-256 UTF-8 canonical JSON по RFC8785 с дополнительной
нормализацией типов ниже. Порядок object keys несущественен, порядок массивов
существенен. Дубликаты JSON keys отвергаются до канонизации; 1 и 1.0 не
преобразуются эвристически. semanticDigest включает IDs, types, methods,
параметры и dependencies; reviewDigest дополнительно связывает показанные
названия, diff, выбранный scope и side effects. Переименование не требует
перезапуска расчёта, но смена отображённого объекта инвалидирует старый review.

## 2. Типы и значения

| Тип | Wire value | Правила |
| --- | --- | --- |
| BOOLEAN | JSON true/false | Без 0/1 и строковых truthy conversions |
| INT64 | Десятичная строка | Signed 64-bit; без exponent/leading plus/leading zeros |
| DECIMAL | Нормализованная десятичная строка | precision ≤38, schema scale 0..12; без NaN/Infinity/exponent; overflow/error, rounding только явно |
| TEXT | UTF-8 string | Schema maxLength, Unicode NFC; не становится ID или кодом |
| ENUM | Exact string key | Из опубликованного bounded enum; label отдельно |
| DATE | YYYY-MM-DD | Календарная дата, без неявной timezone |
| INSTANT | UTC RFC3339, до 6 fractional digits | Offset input нормализуется явно; leap-second input не поддерживается |
| DURATION | Signed integer microseconds string | Отдельный тип; месяцы/годы не являются фиксированной длительностью |
| POINT | {longitude,latitude} | Finite WGS84 degrees; longitude first при GeoJSON |
| REF | UUID | Target kind/collection зафиксирован в field schema; не arbitrary URL |
| INTERVAL | {start:INSTANT,end:INSTANT или null} | Half-open [start,end); null end — незавершённое наблюдение |

DECIMAL canonicalization removes insignificant fractional trailing zeros and
normalizes negative zero; declared field scale/rounding remains part of schema.
Digits/exponents outside the accepted lexical contract reject before conversion.
REF target is either {kind:DATASET,datasetId} or
{kind:RESOURCE,resourceKind:DEVICE|SOURCE|SPATIAL_FEATURE}. There is no ambiguous
built-in target.id. Its value UUID denotes the actual authorized record/resource.

Field имеет id, label, type, nullable, unit, maxLength/precision/scale/enum/target
согласно типу. Единица — зарегистрированный unit ID и dimension, а не label.
INT64/DECIMAL values хранятся native SQL types, строковое wire значение защищает
точность между JavaScript/API/SQL. POINT — единственный scalar с floating
coordinates; геометрический tolerance определяется отдельной quality policy.

COLLECTION.keyFieldIds — ordered composite unique business key (0..4 fields).
Nonempty key uses nonnullable TEXT/ENUM/INT64/DATE/INSTANT/REF fields and is
immutable except reviewed migration. Native owner enforces uniqueness in one
table; explicit filter indexes are separate from this required key index.
Empty native key means only system record_id: owner reserves and persists a
systemFields.recordIdFieldId UUID at creation, exposes it in describe as a
read-only REF to this dataset, and accepts it in MUTATION.key for UPDATE/DELETE.
It is not a user field, label or an extra relation. A native INSERT omits this
key and gets its reserved record UUID in the receipt. Business-key INSERT sends
the full key in key, remaining fields in values. Fields cannot occur in both.
EXTERNAL keys must match verified source uniqueness/order; empty means
unaddressable read-only source. PROJECTED inherits source identity and is read-only.
An import keyFields must match the declared nonempty collection key exactly;
upsert/replace cannot invent uniqueness from matching displayed values.

Отсутствие required field — ошибка валидации. Явный null разрешён только nullable.
Literal {type:NULL,value:null} clears a nullable field in a mutation; NULL is
a value marker, not a user-declarable field type. INSERT omits only nullable
fields (stored null); UPDATE omission retains the old value. Empty values arrays
are forbidden for UPDATE. DELETE has an empty values array. Keys cannot be null
or mutated in place. Required fields cannot be cleared by NULL.

Runtime Unknown(reason,evidence) не является допустимой пользовательской
константой, null или false: он возникает из отсутствия измерения/отношения,
неопределённого времени, покрытия или недоступной версии.

Нет неявных text→number, string→bool, unit conversion или latest-value joins.
convert_unit разрешает только зарегистрированные совместимые единицы;
convert_unit handles registered affine absolute-temperature conversions;
arithmetic on absolute temperature is rejected as UNIT_ARITHMETIC_UNSUPPORTED.
DURATION нельзя
сравнить с DECIMAL hours без явного преобразования. Сравнение REF по label запрещено.

## 3. Scalar expressions

Expression — CONST, FIELD или CALL. FIELD обращается к fieldId в текущем
типизированном record scope. CALL допускает только методы из таблицы.
Аргументы позиционные, arity и типы обязательны. CONST несёт тип и typed value.

| Метод | Сигнатура | Результат/ошибки |
| --- | --- | --- |
| eq / ne | (T,T) → Truth | Типы и unit dimensions совместимы; null/Unknown → U |
| lt / le / gt / ge | (Ordered<T>,T) → Truth | TEXT ordering не используется как numeric/date ordering |
| and / or | (Truth,Truth) → Truth | Kleene logic; false AND U=false, true OR U=true |
| not | (Truth) → Truth | not U=U; не доказывает отсутствие события |
| add / subtract | (Numeric<T>,T) → T | Checked overflow; одинаковая unit dimension |
| multiply / divide | (DECIMAL,DECIMAL) → DECIMAL | Выходные precision/scale pinned; division by zero — ERROR |
| convert_unit | (Numeric,CONST TEXT UnitId) → DECIMAL | Только зарегистрированное преобразование, bounded cost |
| duration_from | (DECIMAL,DurationUnit) → DURATION | Явная точность; не календарные месяцы |
| text_eq / text_prefix | (TEXT,TEXT) → Truth | Case-sensitive NFC; locale folding только явный approved mapping |
| is_known | (T) → Truth | Диагностика; не может заменить coverage/absence guard |
| is_null | (T?) → Truth | T for known null, F for known non-null, U for Unknown |
| distance_m | (POINT,POINT) → DECIMAL<m> | Registered geodesic kernel, finite WGS84; tolerance version pinned |
| inside | (POINT,REF<SpatialFeature>,DECIMAL<m>) → Truth | Accuracy-aware membership against authorized local feature revision; no network lookup |

Truth ∈ T,F,U. Execution status отдельно: OK | UNKNOWN | INVALID_INPUT |
SCHEMA_UNAVAILABLE | ACCESS_REVOKED | STATE_LIMIT | OPERATOR_ERROR.
ERROR не превращается в ложь. Planner/compiler/infrastructure ошибки не
исправляются default false. Every scalar evaluator is pure and bounded.
Предельная глубина 16 и 128 scalar nodes на правило; compiler считает стоимость.
Текстовый CEL/SQL backend в этой версии не используется: один canonical AST,
registered pure kernels. Поздний expression editor должен компилировать туда же.

is_known returns T for known null/non-null and F for Unknown, and cannot grant
access to a denied field. eq/ne involving known null return U; is_null is the
explicit null test. Numeric literals without unit mean dimensionless 1.
convert_unit's second argument is CONST TEXT naming a registered unit; it returns
DECIMAL, with decimalPolicy applied. duration_from accepts dimensionless DECIMAL
and CONST TEXT us|ms|s|min|h; output is checked INT64 microseconds. No exponent or
calendar-month conversion. multiply/divide reject absolute temperature and require
a registered result dimension/canonical unit, otherwise UNIT_RESULT_UNREGISTERED.
Nonterminating division under rounding=REJECT is OPERATOR_ERROR, not silent rounding.

Dates/instants use Gregorian years 0001..9999; instant precision beyond six digits
requires explicit approved mapping, never silent truncation. Scale must not exceed
precision. Literal numbers that pass a regex still undergo range/precision checks.

Implemented scalar profile `vcl-scalar-kernels-1` is published by the shared
VclScalarContracts registry. registryDigest binds grammar plus this profile.
WGS84 geometry pins `geographiclib-java-2.1-wgs84-nm1-v1`: GeographicLib-Java2.1,
explicit HALF_EVEN distance quantization to9 fractional metre digits and conservative
0.000001m kernel error. distance_m subsequently applies decimalPolicy, including
REJECT for a smaller output scale. inside adds this error to reported accuracy;
known null/unknown accuracy stays U, negative accuracy rejects. Pure local CIRCLE
snapshots pin IDs/revisions/geometry; current bounds are128 features, positive
radius<=20100000m. Fresh owner authorization is separate from those data pins.
Polygons and zone-session state remain separately gated. This profile is not an
activation capability announcement; actual owner/UI/AI publication remains pending.

## 4. Методы графа

Graph — directed acyclic list typed nodes. Node = id, method, inputs, config.
Все связи forward references разрешаются после проверки полного DAG;
цикл, неизвестный output port или несовместимый type отвергается.
Event record имеет account/source/subject identity, occurredAt, acceptedAt,
sourceEventKey, schemaRevision, captureGeneration и quality. Эти поля доверенные;
model/user payload не определяет accountId или capture completeness.

Exactly one emit_alert node is required, it equals terminal and has no downstream
consumer. Every node must be an ancestor of terminal; unused branches are rejected.
Each input value is a node ID referencing that node's single typed output; no
implicit port name or array expansion. Inputs with different sessions/subjects
cannot be joined merely because both have the same structural type.
Rules require timing {allowedLatenessUs,maxFutureSkewUs,idleTimeoutUs}; no defaults.

| Метод | Inputs → outputs | Обязательные config/семантика |
| --- | --- | --- |
| read_stream | binding → Events<R> | Approved source/schema/generation, subject scope; нет SQL/URL |
| lookup_asof | Events<R> или Intervals<R> → enriched/split records | Dataset/schema, key pairs, selected fields, temporal EVENT_TIME/INTERVAL_SPLIT, EXACT_ONE на каждый момент; maxDepth≤2 |
| filter | Events<R> → Events<R> | Predicate Truth; T passes, F drops, U records unknown eligibility |
| zone_session | Events<R> → Sessions<R> | Subject/occurrence/zone ref fields, enter/exit quality, debounce, maxDuration |
| window_session | Events<R> → Sessions<R> | Subject/occurrence, typed startWhen/closeWhen, finite maxDuration |
| observed_within | Sessions<R> → Witnesses | Predicate over actual session observations, minimum covered duration |
| absent_within | Sessions<R>,Witnesses → Decisions | Closed window, coverage contract, lateness; never raw NOT(any) |
| interval_union_window | Events<Interval> → Measures<DURATION> | Subject, start/end, include/exclude predicates, calendar, timezone, window |
| sustained | Events<R> → Decisions | Subject, predicate, required duration, maxGap, episode identity |
| window_aggregate | Events<R> → Measures<T> | Subject/value, COUNT/SUM/MIN/MAX/AVG, fixed/rolling/calendar window, output field |
| compare | Measures<T> → Decisions | Typed predicate over measure; explicit unknown/error policy |
| emit_alert | Decisions → AlertProposals | Only T eligible, rule identity, dedup key fields, severity, evidence policy |

Each method has exactly the named input ports and config fields defined by its
registry contract. No undeclared argument bag; JSON Schema references the method
variants and compiler validates field IDs, outputs and generic type substitutions.
read_stream accepts metadata bindings, not data rows embedded as an executable
stream. lookup_asof queries a local authorized materialized projection.
It cannot call a remote database per event, widen read scope or select first match.

interval_union_window computes measure of union(included intervals) minus union
of approved exclusions intersected with work intervals. Split at assignment
changes and calendar boundaries. Overlap counted once. Open/corrected/ambiguous
intervals propagate quality; negative durations and overlapping exclusive
assignments reject or become UNKNOWN under declared input eligibility.
Local day is a calendar window in pinned IANA zone; elapsed time uses Instants.
Week/month windows use that same calendar; weekStartsOn is required for weeks.
A day crossing DST is not assumed 24h. Unknown breaks prevent claiming exact net work.

observed_within.minimumDurationUs=0 requires at least one actual eligible T
observation in the session; it does not succeed vacuously on an empty session.
Positive duration requires covered continuous witness under the declared hold/gap
contract, not count(samples) or an inferred duration from one instantaneous event.
An OPEN event of shorter duration can satisfy zero-duration observation but not
a stronger explicitly approved sustained predicate. See the
[bus expressibility/readiness audit](configurable-operations-bus-readiness.md)
for the required canonical occurrence input and the distinction from non-arrival.

sustained uses measured samples plus explicit sample-hold/maxGap contract.
Direct-journal sustained rules apply upstream filters to both live samples
and replay. An unknown filter result interrupts a confirmed run.
No interpolation beyond approved hold. Unknown gap prevents an unsupported
continuous-duration conclusion; known false resets the episode. A known positive
duration can produce a decision before the enclosing day ends only if its
lower bound alone proves the predicate under all permitted unknowns.

zone_session: first point inside is LEFT_CENSORED; enter requires observed
OUTSIDE→INSIDE. d+a≤r gives INSIDE; d−a>r gives OUTSIDE; otherwise U.
a is actual accuracy or an explicitly approved policy uncertainty floor.
Missing accuracy cannot silently become zero. Exit debounce retains r.
Effective accuracy is max(reported nonnegative accuracy, configured floor), plus
the pinned kernel error bound. Missing accuracy uses the floor only with
accuracyFallback=APPROVED_FLOOR and a strictly positive floor; otherwise U.
minimumExitObservations independently requires that many actual OUTSIDE samples
over exitDebounceUs; a clock tick cannot supply missing observations.
No interpolation between outside points as proof of entry. Radius/feature
revision pinned at session start; source/trip binding changes close UNKNOWN.

window_session applies explicit startWhen/closeWhen over actual observations;
missing start is LEFT_CENSORED and timeout without required coverage is UNKNOWN.
zone_session is a registered specialization with the same state/quality kernel.
window_aggregate declares FIXED, ROLLING or CALENDAR window and reducer.
COUNT requires null valueFieldId; other reducers require a known typed field.
SUM/AVG preserve unit and decimalPolicy; MIN/MAX preserve input ordering/type.
No reading-count-as-duration shortcut. ON_CLOSE waits for finalization;
LOWER_BOUND_PROVEN is legal only if the compiler proves the downstream monotone
predicate from a valid lower bound. Arbitrary partial aggregates cannot claim
complete-window results. More than one rolling window per event is charged
against bounded fan-out before publication.

Pure supplied-batch reduction is distinct from finalization. COUNT counts actual
eligible events, with no value field; SUM computes an exact bounded sum and then
applies the output range/decimal policy, independent of input order. AVG of INT64
returns DECIMAL; AVG of DURATION must be exact integral microseconds or fail.
MIN/MAX preserve ordered type (TEXT uses Unicode code-point order). Selected
UNKNOWN or nullable NULL makes the reduction UNKNOWN, never a silently skipped
measurement; nonnullable NULL is invalid. Empty COUNT/SUM have arithmetic zero,
while empty MIN/MAX/AVG are UNKNOWN(AGGREGATE_EMPTY). None of these batch outcomes
establishes window completeness or satisfies ON_CLOSE/LOWER_BOUND_PROVEN gates.

All stateful operators finalize using rule.timing.allowedLatenessUs.
absent_within.allowedLatenessUs must equal that value, not add another grace.
sustained.maxGapUs may be stricter than coverage.maxGapUs, never looser.
interval_union_window also declares emit=ON_CLOSE|LOWER_BOUND_PROVEN.
The lower-bound mode is supported only for COUNT or interval union with a covered,
finalized reference/break prefix and downstream bare gt/ge(measure,constant).
For a covered COUNT prefix, known eligible events contribute to the bound
even when other rows have unknown eligibility. A bound below the threshold
leaves the full-window decision UNKNOWN. The exact COUNT still requires
resolved eligibility across the window.
Unsupported proof shapes are rejected; do not add an SMT/guessing fallback.
Late explicit corrections can amend the earlier evidence-bound conclusion.

## 5. Compiler and state

Compilation stages: strict parse → resource/access resolution → generic type/unit
checking → DAG/operator checks → reference projection readiness/index check →
cost/state estimation → immutable CompiledBundle. Output contains VCL digest,
operator versions, schema/mapping/zone/policy revisions, execution grant ID,
input generation, effective-time lookup policy, key definition and start boundary.

Maximum graph nodes 64; lookup hops 2; one lookup match per key; 32 selected
runtime fields; 31-day window; finite explicit session timeout; no unbounded
fan-out. Deployment has per-account memory/state/CPU/backlog budgets.
Limits can be raised only as a versioned server policy with measured evidence,
not by model request. Operator registry advertises the current exact limits.

State key = account + logicalRuleId + deploymentEpoch + subject + window/session.
Input handled under a fenced single-key lease; state revision, inbox mark, timers,
derived output and outbox commit together. Rekey to employee/other subject uses a
durable internal keyed message, not in-memory cross-partition mutation.
Restart replays committed inputs; duplicate source event/revision is a no-op.
One notification side effect is not promised physically exactly once.

The 31-day bound applies to fixed/rolling elapsed windows. A calendar month is
one local month and may occupy up to 32 elapsed days including offset transitions;
state/retention budgets include this and lateness. Window intervals are [start,end),
events exactly at end enter the next window. weekStartsOn uses ISO 1=Monday..7=Sunday.
An INTERVAL_SPLIT lookup feeds interval_union_window with the same start and
end fields. Chained splits keep that pair and selected lookup fields cannot
overwrite the bounds. Analytics checks the total number of window assignments
for a report before staging its timers.
idleTimeoutUs and maxDurationUs are positive; timeout without closed covered evidence
produces UNKNOWN. Overskew future events are quarantined, not used to move watermarks.

Configuration revision pins differ from live row references:
- Schema/operator/policy constants immutable in deployment.
- Relations resolve validAt=event time at a specified recorded cutoff.
- Execution evidence stores actual row revisions and reference checkpoint.
- Late corrections trigger a new evaluation revision on affected bounded keys.
- Replaying original output uses its original cutoff, not current rows.

Watermark is the minimum trusted progress of required inputs/reference projections,
with configured lateness. Wall clock closes an observation wait as UNKNOWN when
coverage cannot be established. Absence operator needs closed covered window,
not just broker heartbeat. Upstream loss without sequence/replay cannot be ruled out;
the conclusion is scoped to observed evidence, not guaranteed physical truth.

## 6. Lifecycle and effects

DRAFT → VALIDATED → PREPARED → ACTIVATING → ACTIVE.
ACTIVE can be DEGRADED/PAUSED; disabled/deleted grant blocks new effects.
PREPARED refers to immutable digest and validation manifest. Activation epoch
has an evaluator readiness ACK and durable start checkpoint before ACTIVE.

Rule edits create a new revision. Default cutover at next unopened window/session;
already-open state drains on pinned old revision. Explicit reevaluation of current
window is a reviewed correction, not a second live alert stream.
Logical dedup key excludes deployment epoch: rule + subject + business occurrence.
Config changes cannot produce duplicate alerts for one physical visit/workday.
Splitting/merging rule identity is a reviewed operation with new identities.

PAUSE stops new windows/effects and closes unfinished windows UNKNOWN(PAUSED).
RESUME creates a new epoch/checkpoint with explicit left-censoring; it does not
catch up notifications for paused time. Reevaluate paused intervals is a separate
reviewed job with effects disabled by default. Editing an active rule drains old
windows; a grace/geometry change never mutates their captured configuration.

Generic output creates evidence-backed alert proposals. alert-service owns
lifecycle/dedup/suppression and existing notification ports. Evidence correction
appends outcome revision and updates the same logical alert. Opening a door on the
next stop does not erase a previous visit. Arbitrary external writes are not
rule side effects; they use a separate reviewed adapter mutation contract.

## 7. Control methods used by UI and AI

| Method | Input preconditions | Output |
| --- | --- | --- |
| datasets.describe | Authorized dataset ID | Pinned schema/capabilities/scope |
| datasets.query | QUERY document and bounded cursor | Typed rows, read manifest, completeness |
| datasets.prepare_mutation | Exact target/read revisions, typed diff | Review/digest/dependencies |
| datasets.apply | Confirmed review, expected versions, idempotency key | Owner receipt or UNKNOWN |
| connections.describe/test | Authorized connection config/secretRef | Typed capabilities/diagnostics, no secret |
| imports.validate | IMPORT document + sealed artifact | Immutable ValidationManifest |
| imports.apply | Confirmed manifest digest and expected target versions | Job/receipt and exact row outcomes |
| spatial.prepare/apply | Authorized geometry diff | Same reviewed resource receipts |
| rules.compile/dry_run | RULE document and pinned test inputs | Bundle/report; no live effects |
| rules.activate/pause | Prepared bundle/grant/versions + confirmation | Epoch/status/receipt |
| reviews.inspect/reconcile | Owned review/receipt identity | Stored exact outcome; no model required |

Neither UI nor AI gets privileged generic SQL. AI metadata discovery is paged,
scoped and field-aware; schema names and notes are untrusted text.
All mutations reauthorize at owner boundary; a prepared review is not a lasting
permission. Model mode/routing never changes owner method capabilities.

CONNECTION document selects an approved endpointRef, secretRef, adapter/version,
object allowlist, TLS policy and expected config revision; it contains no raw
credential. A model cannot allocate network authority by inventing a reference.
MUTATION document specifies exact target schema, operation, typed key/values,
row version, review, idempotency key and discriminated targetPrecondition.
Only EXTERNAL includes a verified adapter contract and source fingerprint.
UPDATE/DELETE require a row version; DELETE carries no values.
Owner approval/grants remain separate from merely passing document validation.

Exact owner HTTP/review/dependent-step contracts and documentation checks:
[control protocol](configurable-operations-control.md).

