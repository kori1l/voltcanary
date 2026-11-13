# Хранение данных, внешние БД и выполнение

Статус: **целевая спецификация**, 2026-11-03. Native owner реализует schema
review/provision/describe, reviewed single-row CAS/history capture и ограниченный
LIVE и sealed SNAPSHOT query profiles. Import/migration, внешние адаптеры и performance
acceptance ещё не завершены. Текущий срез описан в
[owner API](../current/api/configurable-native-data.md).
Общий язык: [VCL/1](configurable-operations-language.md).
[Импорты и mutations](configurable-operations-imports.md).

## 1. Решение о способах хранения

Dataset — logical schema + source binding + capabilities, а не обещание
скопировать данные в VoltCanary. Три явных режима:

| Mode | Где authoritative данные | Стоимость/применение |
| --- | --- | --- |
| NATIVE | Managed typed SQL table VoltCanary | CRUD, таблицы без внешнего источника |
| EXTERNAL | Существующая таблица/view/API клиента | Bounded direct reads, approved mutations; копия не обязательна |
| PROJECTED | Выбранные fields/rows внешнего dataset в локальной версии | Temporal evaluation, stable dry-run, history; явно включаемая стоимость |

Overlay metadata (пометки, zone links, UI preferences) хранится отдельно с
external record ref, не меняет клиентскую строку. Не создавать копию всей БД
ради фильтрации или списка. При publish правила compiler перечисляет конкретные
поля/строки/историю, требующие проекции, и показывает ресурсную стоимость.
Direct-only source без snapshot/history не обещает temporal completeness.

## 2. Native physical layout — плотная строка

Выбор: **server-provisioned typed table per native collection**, а не EAV и не
разложение каждого scalar по нескольким таблицам. Внешние таблицы не меняются.
Поля создаются metadata compiler из allowlisted SQL types; клиент/AI не пишет DDL.

Physical name генерируется server-side из UUID: cfg_data_<uuidhex>.
В строке: record_id UUID PK, record_version BIGINT, created_at/updated_at,
external_key (optional unique within dataset) и реальные типизированные columns.
Native SQL types: BOOLEAN, BIGINT, NUMERIC(38,s), bounded TEXT, DATE,
TIMESTAMPTZ, duration BIGINT microseconds, POINT as longitude/latitude doubles,
REF UUID; source target/type зафиксирован schema, INTERVAL — start/end.
Простое чтение списка требует **одного SELECT из одной таблицы**, плюс cached
schema/access lookup. Reference labels подгружаются отдельным bounded batch
по запросу UI; не обязательным join для каждой строки.

Metadata shared tables: cfg_dataset, cfg_schema_revision, cfg_field,
cfg_source_binding, cfg_index_manifest, cfg_mutation_receipt, cfg_outbox.
Metadata не является EAV value storage. Даже если collection имеет REF,
relation edge table не создаётся автоматически: ID хранится inline.
Assignment/M:N с собственными свойствами — обычная явно созданная collection.
Temporal overlap guard включается только для такой relation collection.

Illustrative server-generated PostgreSQL16 layout, not an executed migration:

```sql
CREATE TABLE cfg_data_00000000000040008000000000000001 (
    record_id uuid PRIMARY KEY,
    record_version bigint NOT NULL CHECK (record_version > 0),
    external_key varchar(160) UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    f_label varchar(120) NOT NULL,
    f_amount numeric(18, 3),
    f_enabled boolean NOT NULL,
    f_related_record uuid
);
```

The last column is an optional typed reference value; it does not force an edge
table or join. Its target/scope is validated by the owner contract. A list reads
selected columns from this one table. Physical field names are generated stable
tokens from trusted schema metadata, never labels interpolated into SQL.
Only declared field indexes are provisioned. Immutable schema revisions can keep
old physical columns while pinned readers need them; destructive type/unit changes
create reviewed migration/new fields, not in-place semantic reinterpretation.

История payload — opt-in HISTORY capability; audit actor/digest/receipt обязателен
для всех mutations. История требует отдельной dense revision table только
у включивших её dataset. Snapshot/replay rule требует history или sealed snapshot;
компилятор не симулирует прошлое из текущих строк. Source/telemetry time series
остаются в своих существующих stores, не копируются как current config rows.

## 3. DDL, индексы и эксплуатационная стоимость

Schema draft → validate → estimate/migration preview → provision → READY.
DDL — доверенный system job с lock_timeout/statement_timeout, exact table IDs
и rollback/reconcile journal. До READY table не доступна обычным API.
Это изменение adapter schema по проверенной metadata, не runtime eval SQL.

Индексы создаются только на primary/external key и явно выбранных sort/filter/
lookup fields. Max indexed fields — server policy; UI показывает write/storage cost.
Не создавать GIN/all-field indexes, inverse relations и FK graph каждому клиенту.
Query выбирает prepared parameterized SQL по server field-to-column mapping.
Неиндексированный дорогой filter/sort для большого dataset возвращает INDEX_REQUIRED
с планом подготовки индекса, а не неожиданно сканирует всё в hot path.

CREATE INDEX CONCURRENTLY имеет отдельный job/receipt; потерянный ответ сверяется
по trusted index identity и validity, не создаёт второй индекс. Schema publish
не считает pending index готовым. Native DDL/listing catalog cost измеряется
на target числе datasets: много маленьких таблиц имеет PostgreSQL catalog cost.
Quotas на native datasets, columns, indexes и provisioning concurrency обязательны.
Крупные существующие БД выгоднее подключать EXTERNAL/PROJECTED. Массовый перенос
клиентов на другой layout требует отдельного migration decision/evidence.

H2 — функциональные contract tests. PostgreSQL 16, используемый Compose,
обязателен для DDL locks, indexes, isolation и performance acceptance.
Никакого вывода о production latency по H2.

## 4. Native consistency и запросы

Создание/изменение ≤500 строк одной native collection атомарно в локальной
транзакции. expectedRecordVersion проверяется CAS; idempotency key + canonical
payloadDigest + stored response commit вместе. Повтор того же key с другим
payload →409. Domain data/outbox/receipt commit together. Нет distributed 2PC.

Операции нескольких owner services — saga существующего plan runtime.
Успешные ресурсы сохраняются при последующем сбое. Компенсация только отдельная
reviewed команда, не автоматическое удаление того, что пользователь уже использует.

QUERY: selected field IDs, typed predicate, ordering, limit≤100, opaque seek cursor.
Нет offset pagination для длинных reads и нет raw SQL/count-all по умолчанию.
LIVE чтение не обещает неизменяемого набора между страницами.
SNAPSHOT чтение использует sealed materialized snapshot ID с access recheck,
field scope, source consistency и cutoff; создание snapshot — explicit bounded job.
Counts/aggregates имеют same snapshot/authorization и known completeness.

Не держать SQL transaction открытой в течение разговора с AI.
Dry-run фиксирует data cutoff, schema/binding versions, input IDs/quality.
Стабильная выборка создаётся отдельным short-lived snapshot job с TTL.
Истёкший snapshot → SNAPSHOT_EXPIRED, не автоматическое продолжение по latest.

## 5. Контракт внешнего адаптера

ConnectionDefinition: id, account/workspace scope, adapterId/version,
approved endpoint/network route, database/catalog, secretRef, TLS policy,
read/write capability grant, allowed object manifest, schemaFingerprint.
Passwords/connection strings не передаются модели или в импортный JSON.
Частная сеть клиента разрешается через явно настроенный egress/connector route;
произвольный host, redirect и internal URL от модели не разрешаются.

Подключение: reviewed config → connectivity test → bounded introspection →
user-selected tables/views/columns → mapping preview → publish binding.
Discovery читает только approved catalog scope. Existing database schema
остаётся authoritative, metadata VoltCanary ссылается на object/key fingerprint.

Adapter methods:
- describeCapabilities(connection): dialect, type mapping, snapshot/CDC/poll,
  stable key, supported predicates, read consistency, write/CAS/receipt abilities.
- introspect(selection, expectedFingerprint): typed table/view/field/key metadata.
- query(QueryPlan, ReadScope, Cursor): bounded pushdown, typed rows, cursor,
  source checkpoint, completeness and freshness.
- snapshot(Selection, limits): sealed consistent snapshot or UNSUPPORTED;
  READ_COMMITTED pages cannot pretend to be one snapshot.
- changes(Checkpoint, selection): change records, deletes, ordering and gaps;
  poll without delete detection advertises that absence is not known.
- prepareMutation/executionStatus/apply: only exact granted write contract below.
- health: bounded safe diagnostics, never passwords/raw SQL parameters.

Native column names are quoted identifiers from introspected allowlist, not string
fragments supplied in VCL. Values are parameters. Read-only connection uses a
read-only DB role and statement/row/byte/time limits. Read/write require separate
grants and capability checks. Source DB permissions and platform permissions
intersect; shared source credential does not grant all UI users database access.

Predicate pushdown must preserve VCL semantics: Unicode/case/collation, null,
decimal precision, date/instant/timezone and reference identity. text_prefix
uses literal prefix semantics; SQL wildcard characters are escaped, not treated
as a supplied LIKE pattern. A vendor's case-insensitive equality cannot silently
implement VCL text_eq. Unsupported pushdown is rejected or uses an explicitly
bounded local post-filter; if limits stop that scan, result is INCOMPLETE.
No silent SELECT-all fallback and no complete-negative claim from a truncated
post-filter. Dialect conformance fixtures are part of adapter qualification.

External key includes connection/object stable IDs and typed composite key values.
Keys are required for reliable updates/dedup; missing/nonunique key → explicit
read-only unaddressable capability, not guessed row numbers. Raw sensitive key
values are not included in logs or model traces. Dataset revision pins mapping
and source schema fingerprint. Changed type/key/removal → SCHEMA_DRIFT, pause
affected mapping and typed diagnostic; no automatic widening or lossy conversion.

## 6. Запись в клиентскую БД

EXTERNAL write is supported **only through a declared verified mutation adapter**.
Contract requires deterministic target key, expected source row version or
equivalent CAS, parameterized allowed operation, transaction boundaries,
idempotency receipt/status lookup and safe read-after-write evidence.
Write scopes enumerate objects, fields and allowed INSERT/UPDATE/DELETE;
DELETE is separately granted/reviewed. Schema migration and arbitrary SQL are
not implied by data write access.

A source may expose an existing idempotent API/stored operation. For plain SQL,
a connector-side receipt table in the **same customer DB transaction** may be
provisioned only with explicit customer approval. No approval/receipt/CAS ability
→ writes unsupported, even if a generic DB login could issue UPDATE.
This is an adapter capability condition, not silent best-effort writes.
Never claim guaranteed idempotence from an eventually reread row with same values.

Timeout after mutation → UNKNOWN. Orchestrator calls executionStatus using the
same stable key. It cannot blindly reissue with a new key or create a second
resource. CAS conflict requires a new diff and confirmation. Remote commit
followed by local failure is reconciled from remote receipt; cross-DB atomicity
is not promised. Mixed-native/external bulk job exposes per-target outcomes.

## 7. Projections, deletion и два времени

Projection materializes only subscribed selection and maintains source checkpoint,
acceptedAt and reference generation. Compiler declares required freshness,
delete/correction detection and retained history. A poll with updatedAt alone
cannot prove complete deletion capture. Source capabilities are verified, not
inferred from a successful sample query.

valid_time defines when a row/assignment was true in the business world.
recorded_time/checkpoint defines when the platform learned a revision.
Historical lookup explicitly selects both. Correction at 15:00 of an assignment
valid at 09:00 cannot overwrite the original 10:00 decision without an auditable
reevaluation/correction event. Bounded impact index maps affected rule/window keys.

A missing reference projection parks bounded work before UNKNOWN/FAILED handling;
consumer ACK never silently drops required data. Old versions retained while
referenced by active state/replay/evidence. Retention expiry produces
EVIDENCE_EXPIRED/REEVALUATION_UNAVAILABLE, not fabricated reconstruction.
Withdraw/archive preserves required evidence according to explicit retention policy.

## 8. Security и automation identity

Human operations resolve current account/workspace grants. Autonomous evaluation
uses explicit revocable AutomationGrant(id, ownerAccount, allowed sources/fields,
datasets, output channels, rule scope, version, expiry/revocation epoch).
A saved user JWT or stale user's broad role is not the execution identity.

AutomationGrant authority belongs to alert-service alongside rule activation.
analytics can persist a proposal after revocation, but alert-service locks and
checks its current grant row/epoch in the transaction that creates an alert/
delivery intent. A proposal is not an externally committed effect.
Grant revoke is linearized by the same owner before its successful ACK.
Source/workspace rights are additionally revalidated against their actual owners
at publish and effect authorization; an asynchronously cached ACL is insufficient.
If that owner cannot answer, the effect is held/denied, not permitted from stale data.

Global source/workspace revocation must not be advertised as instantly propagated
to every service merely because one outbox event was written. The new automation
control exposes REVOKING until dependent active grants are fenced/acknowledged.
Existing legacy source API guarantees are not silently strengthened by this design.
Dispatch authorization is a short-lived persisted permit with the checked grant
epoch; revocation prevents new permits. Already-authorized in-flight sends are
reported separately and cannot be promised recall across a remote provider.

Publish validates the principal's authority to create the grant; effect owner checks
its current epoch before committing effects. Reads and delivery recheck current
access. Sensitive input classification propagates to derived values/evidence;
alert recipients must be permitted to see the disclosed data. A predicate on
a hidden field can reveal that field; masking just the raw value is insufficient.
Public dashboard sharing cannot widen source/table permissions.

Platform scope must be enforced in metadata lookup, SQL compilation, projections,
cache keys, counts, exports and traces. Do not present filtered inaccessible rows
as a complete business dataset. Insufficient scope yields DENIED/INCOMPLETE
without disclosing foreign counts. Arbitrary user-authored row-policy languages
are not necessary: existing grants plus explicit field classification apply.
Optional finer row policies require the same query/compiler contract.

Revocation of the AutomationGrant commits its new epoch before acknowledgement;
effect transactions fence against it. A notification already sent to a remote
provider cannot be recalled. Pending delivery checks epoch; audit records the
precise authorization/send/revocation boundary.

## 9. Execution isolation and transaction boundaries

Connection pools and worker queues for customer sources are separate from the
owner metadata DB pool and keyed by connection/secret revision. Demand-created
pools have idle expiry and global/per-account concurrency/row/byte/deadline caps.
A slow or failed customer DB must not exhaust ordinary metadata/UI capacity.
Remote JDBC transactions are explicitly source-local; an owner application
transaction must not imply a distributed commit of both databases.

Remote mutation sequence: persist local prepared intent → execute remote
data+receipt transaction → collect remote receipt → commit local final outcome.
Crash at any boundary resumes by receipt/status with the same key. Cross-source
bundles report each outcome and never hide partial commits behind one SUCCESS.

Compilation/review uses a read-set vector of exact owner revisions/checkpoints,
not a fictitious globally atomic snapshot across databases. Each input advertises
consistency/freshness and allowed clock skew. If required alignment cannot be
proved, readiness/dry-run reports UNKNOWN or waits. Event time is never silently
rewritten to arrival time to force a match.

## 10. Release evidence и capacity

Measure ordinary no-relations list/get/write against equivalent dense hand-written
table with same schema/index/security/audit. Declare hardware/dataset/workload,
p50/p95/p99, plans, rows examined, CPU/IO, WAL, index/history overhead and catalog
growth. Proposed gate: common reads/writes ≤20% overhead versus equivalent
baseline at declared load, no hot-path accidental full scans or per-event remote
lookups. If unmet, release is blocked; architecture text is not proof of speed.
Optional history/projection/relations are separately priced/measured.
No promises for arbitrary rows, joins, devices or databases without measured envelope.

## 11. Identity, generations and recovery details

Native row IDs are server-reserved UUIDs in the prepared mutation; a retry reuses
that reservation. A native import key is the canonical typed tuple of declared
key field IDs/values, hashed to a fixed digest for external_key. Compare the full
tuple on collision; never use a user label as identity accidentally.

External record IDs do not require a UUID column or a full copied source table.
The adapter computes UUIDv8 from the first 16 bytes of SHA-256 over canonical
UTF-8 JSON ["VCLEXT/1",accountId,connectionId,objectId,keyCodecVersion,keyTuple],
setting byte6 high nibble to 8 and byte8 variant bits to 10.
keyTuple follows declared source key order and preserves exact source TEXT bytes;
VCL display NFC normalization MUST NOT collapse distinct external primary keys.
The UUID is an identifier, not a permission or a reversible locator.
See [RFC9562 UUIDv8](https://www.rfc-editor.org/rfc/rfc9562.html).
keyTuple is an ordered array of source-codec tagged values `{type,value}`;
the published codec defines exact integer/decimal/time/binary/text serialization,
is pinned by keyCodecVersion and tested against source equality. It does not
reuse lossy VCL display conversion. A source key whose equality/serialization
cannot be preserved is UNSUPPORTED_KEY_CODEC, not a guessed string identity.

A source key locator is a separately authorized opaque token. Direct list/query
returns IDs/locators without writing a registry row for every result. When a REF
is persisted, the owner stores only the referenced record's encrypted source key,
codec revision and full digest in cfg_external_locator. Reading an unregistered
external ID requires its valid locator, or returns LOCATOR_REQUIRED; it never
scans a remote table trying to reverse a UUID. Registered digest/key collisions
fail as IDENTITY_COLLISION. Sources without stable keys expose unaddressable
read rows, no stored REF, seek cursor or mutation capability.

External primary key changes produce a new record identity. The old reference
becomes gone/unknown under actual delete/rename evidence; no silent retarget.
Native key changes and external rekey are explicit reviewed migrations.

STAGED_REPLACE builds an owned shadow table/generation, preserves native record
IDs for matching stable keys, reserves IDs for inserts and shows removed refs.
Stage carries expected generation/dataVersion. Apply locks cfg_dataset head,
checks the expected head and atomically swaps the active pointer plus receipt/
outbox. Native writers pin/check that head while their transaction holds its
shared generation fence; a write cannot commit into an obsolete generation after
the swap. Concurrent target edits invalidate the stage before pointer change.
Old generations remain while referenced by a read snapshot or a retention lease.
Immutable schema caches are keyed by revision; mutable active-head routing is
reauthorized/refreshed, not served indefinitely from stale cached metadata.

A stable query cursor binds principal/access-scope digest, query digest, source/
schema/binding revisions, generation or snapshot ID, sort values and expiry.
Ordering appends a unique record key; null ordering is LAST for ASC and FIRST
for DESC. Changing a filter/order/selected fields cannot reuse the cursor.
LIVE can change between pages; SNAPSHOT never silently falls back to LIVE.

Remote executionStatus has distinct COMMITTED, REJECTED, IN_PROGRESS,
NOT_VISIBLE and UNKNOWN. A NOT_FOUND/NOT_VISIBLE receipt read is not proof that
another transaction has not started or committed without visibility. Repeating
APPLY is safe only with the same key/digest under the adapter's verified atomic
in-flight deduplication contract. Status polling is bounded; exhaustion keeps
UNKNOWN and dependent steps blocked. A new key is not a recovery mechanism.

Native MUTATION preconditions use generation/dataVersion plus numeric record
version; EXTERNAL uses bindingRevision/schemaFingerprint/adapterContractId plus
source-opaque row version. INSERT requires expectedRowVersion=null; generated
keys are permitted only if owner/adapter receipts return the stable created key.
UPDATE/DELETE require the full unique target key; key fields cannot appear in
values. A native operation does not require a fictitious remote adapter contract
or remote schema fingerprint.


