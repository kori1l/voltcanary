# Строгие импорты, синхронизация и изменения данных

Статус: **целевая полная спецификация**, 2026-11-03.
[VCL/1](configurable-operations-language.md), [storage/adapters](configurable-operations-storage.md).
Import, live sync и external mutation — разные operations с общими typed mappings;
один расплывчатый «загрузить данные» API не используется.

## 1. ImportDefinition

VCL IMPORT обязателен: contractVersion, artifactId, artifactSha256, target
(datasetId,schemaRevision,mappingRevision), format, encoding, mode,
keyFields, columns, nullPolicy, conflictPolicy, targetPrecondition,
limits и provenance. Server-owned uploaded artifact immutable and scoped;
произвольный URL/SQL/credentials не входит в importer.

Supported canonical formats: UTF-8 CSV по фиксированному CSV contract и JSONL
по одному объекту на строку. ZIP/GTFS, XLSX, vendor APIs идут через registered
format adapters с собственными строгими входными контрактами и limits.
Не угадывать format по расширению и не включать auto-detect типов.

CSV: delimiter explicitly comma/semicolon/tab, header exactly declared,
quote=", doubled quote escaping, CRLF/LF accepted, BOM только UTF-8 в начале.
Duplicate/missing/unknown headers, malformed quoting, inconsistent column count,
invalid UTF-8, duplicate JSON keys and trailing junk — errors before writes.
JSONL: exact declared properties, bounded nesting; top-level arrays запрещены.

Source mapping:
source column + declared sourceType/sourceUnit → 1..4 ordered registered
conversions → target fieldId type/unit checks. Singular conversion is not a valid
revision-2 document. Each step consumes the previous step's typed result.
Conversions: identity, parse_int64, parse_decimal, parse_boolean, parse_date,
parse_instant, parse_duration, convert_unit, resolve_ref.
Every conversion has explicit options; its implementation version is pinned in
the compiler registry/bundle, not an undeclared per-column version property. Нет arbitrary expressions,
downloaded code, regex extraction с неограниченной стоимостью или generic eval.

parse_decimal требует decimal separator и scale/rounding policy; locale
не определяется из браузера. parse_boolean задаёт disjoint true/false token sets.
parse_instant требует RFC3339 offset либо exact local format + IANA zone +
explicit DST ambiguity policy REJECT/EARLIER/LATER; no default machine timezone.
parse_duration задаёт fixed unit/format; P1M не становится 30 днями.
resolve_ref использует authorized target/key mapping и EXACT_ONE; не first/nearest.
Every column declares sourceType and sourceUnit (null means dimensionless/not
applicable, not infer-from-target). CSV cells have sourceType=TEXT. Typed JSONL
values validate against the declared wire type. parse_decimal preserves declared
sourceUnit; convert_unit must agree with it and yields targetUnit. Thus TEXT '220'
with sourceUnit=V → parse_decimal → convert_unit(V,kV) → DECIMAL 0.22 kV.
identity cannot relabel V as kV or turn CSV text into numeric implicitly.
Ordinary imports have no implicit defaults. Missing/null/empty remain distinct;
null is allowed only for nullable targets under the explicit mapping.
Unknown external fields не игнорируются молча.

## 2. Режимы и атомарность

| Mode | Контракт |
| --- | --- |
| ATOMIC_INSERT | ≤500 validated rows одной owner DB; duplicate key отвергает весь batch |
| ATOMIC_UPSERT | ≤500 rows, stable external key и expected target version; local transaction |
| STAGED_REPLACE | New sealed generation; все rows staged/validated, затем atomic active-generation pointer switch |
| CHUNKED_UPSERT | Явно non-atomic job; bounded chunks, per-row receipts/outcomes, resumable progress |

Default mode is never inferred from file content. STAGED_REPLACE must show
deletions/reference impact and requires explicit confirmation; empty file cannot
silently delete a table. CHUNKED mode never reports all-or-nothing.
No cross-service/database atomicity claim. A row update cannot silently overwrite
a newer human edit: CAS conflict is terminal until reviewed again.
For ATOMIC modes limits.maxRows cannot exceed 500 even when the actual file is
smaller. keyFields matches the target COLLECTION.keyFieldIds in exact order.
Without a declared nonempty key, import rejects KEY_REQUIRED before preview;
users can explicitly add a business key, or use individually reserved record CRUD.

Bulk identity = job id + source snapshot hash + mapping digest + target scope +
stable row key. Same job/key same content replays; different content conflicts.
All duplicate keys within one source artifact are rejected. Aggregation requires
a separate reviewed derivation and a new sealed artifact; no importer aggregation
switch exists in the grammar.
External systems' correction sequence is retained, not overwritten by arrival order.

targetPrecondition is a discriminated object: NATIVE={mode,generation UUID,
dataVersion integer}; EXTERNAL={mode,bindingRevision integer,schemaFingerprint}.
Schema revision is already in target. No overloaded expectedTargetVersion field.
Native dataVersion changes on each committed batch; generation changes on replace.
External bindingRevision is configuration concurrency, not a remote table version:
ValidationManifest seals per-target source keys/row versions for remote CAS.
EXTERNAL atomic modes additionally require verified atomic-batch receipt/CAS;
otherwise UNSUPPORTED_IMPORT_MODE before any write. CHUNKED external jobs use
separate deterministic adapter mutation keys per chunk/row, with exact receipts.
User imports cannot edit a PROJECTED mirror; change its source or use an overlay.

## 3. Prepare, validate, approve, apply

Job lifecycle:
UPLOADED → VALIDATING → VALIDATED | REJECTED → PREPARED →
CONFIRMED → APPLYING → SUCCEEDED | PARTIAL | FAILED | UNKNOWN | CANCELLED.

Validation is side-effect-free on target data. ValidationManifest seals:
artifact hash/bytes/row count, exact parser/mapping/schema revisions, target
read/version cut, valid/error counts, complete error summary, reference resolution
manifest, projected inserts/updates/deletes, permission scope, time/row/byte limits,
side effects and digest. Preview samples are labelled samples, not complete success.
A manifest with any rejected row cannot become an ATOMIC-mode approval.

All modes require complete successful source validation before apply. CHUNKED
means partial target commits can occur, not permission to skip malformed input.
Manifest counts: processedRows, validRows, invalidRows, totalRows (nullable until
EOF), unprocessedRows (nullable), validationComplete and displayedErrorsTruncated.
Cancelled/budget-limited validation has validationComplete=false and cannot be
approved; it reports observed counts without inventing exact totals.

Apply accepts manifest ID/digest, owner review/version, sealed targetPrecondition
and idempotency key. It rechecks scope, schema/key/fingerprint and target versions.
Any material drift → VALIDATION_STALE/409 and new preview; the user cannot send
a previously validated=true boolean to bypass validation. Changes to source/mapping,
conversion, target, conflict policy or deletions invalidate consent.

Before reading a file, enforce compressed/uncompressed/file/row/field/depth limits
declared by adapter. Limits are exact counts/bytes, not approximate promises.
Cancellation stops future chunks; committed chunks remain with receipts.
Unknown external outcome freezes dependent actions pending status reconciliation.

## 4. Errors и контроль полноты

Error = code, stage, recordOrdinal, sourceColumnId, targetFieldId, safeReason,
retryable, optional allowedChoices. Raw sensitive values не включаются в logs,
model context или общий error report. Owner may inspect exact affected record
through separately authorized endpoint. At most 100 displayed errors, but exact
invalid count for processed rows and completion/truncation flags required. Over limit cannot produce
«ошибок нет» только потому, что parser stopped.

Codes: FORMAT_INVALID, TYPE_MISMATCH, PRECISION_LOSS, UNIT_MISMATCH,
NULL_NOT_ALLOWED, DUPLICATE_KEY, REF_NOT_FOUND, REF_AMBIGUOUS, REF_DENIED,
SCHEMA_DRIFT, VALIDATION_STALE, TARGET_CONFLICT, LIMIT_EXCEEDED,
SOURCE_UNAVAILABLE, SOURCE_INCOMPLETE, WRITE_OUTCOME_UNKNOWN.
Infrastructure retry retains exact artifact/mapping/keys; data errors require a
new reviewed corrected artifact. No broad retry with relaxed validation.

## 5. Continuous sync

SyncDefinition reuses sealed mapping and source capabilities, but owns checkpoint,
change ordering, delete semantics, polling/CDC policy, gap detection and health.
Initial snapshot + change handoff requires a source-consistent boundary.
If the adapter cannot prove gap-free handoff, projection status INCOMPLETE;
do not call it a complete mirror. Lateness and corrected rows propagate to rules.

Polling without delete detection cannot implement absence of an external row.
Latest-only capture cannot implement absence of a transition. These constraints
are capability checks at rule compilation/activation, not only operating notes.
Schema drift suspends affected fields/jobs without inventing replacements.

## 6. Права, performance и аудит

Every artifact, manifest, job, row receipt and connection is tenant/workspace scoped.
Validation and apply authorize selected fields and linked resources. Import code
does not access forbidden source columns merely to discard them later.
Server compiles bounded bulk statements/prepared parameter batches.
No per-cell remote query: resolve references by bounded key batches, with counts/
cardinality and cache generation recorded. Nothing falls back to N+1 silently.

Capacity tests measure parser memory, rows/sec, row/reference query counts, DB
locks/WAL and rejection/restart behavior. Error-free small samples are not proof
of full-file validity or large-file capacity. Retention and secure deletion apply
to uploaded raw files independently of long-lived receipt/digest audit.

