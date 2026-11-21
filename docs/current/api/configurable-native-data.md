# Native collections, reviewed rows and queries

Current backend checkpoint, 2026-11-17. Device-service exposes reviewed native
schema creation, single-row INSERT/UPDATE/DELETE with CAS, optional dense history
capture, bounded LIVE queries and sealed query snapshots. The /data console provides the first
manual table/row workbench. Bounded atomic imports and reviewed generation
replacement are available through the API described below. Chunked imports and
schema migration/archive routes are now implemented in source but await the
deferred full-feature qualification campaign; their contracts are below.
Workspace field/row grants and shared reviewed row operations are also present
in source and await qualification. External adapters remain pending.
AI has a separate default-off native-table
authoring slice described below; broader AI composition remains pending.
Descriptions return liveQueryReady=true with explicit queryCapabilities;
historyReady reflects the presence of a verified capture checkpoint for the current head. The sealed event-time lookup
described below resolves one captured selection.

### Table action capabilities

`GET /data/datasets/{id}/capabilities` returns a current owner-authorized
`dataset-actions-1` snapshot with `datasetId`, `schemaRevision`,
`generation`, `sourceMode`, `actions` and `unavailableReasons`. Responses
use `Cache-Control: no-store`. The implemented owner publishes `NATIVE`.
Import requires business keys. History depends on a verified history cut.
Reviewed writes still recheck current authority and target preconditions.

The workbench matches capability identity, revision and generation to the
described table before enabling actions. A capability outage or unsupported
snapshot leaves mutation controls disabled while an authorized row page
remains readable. Refresh retries discovery. UI support for owner-published
read-only EXTERNAL/PROJECTED modes is covered with controlled responses;
external runtime adapters remain pending.

## Implementation awaiting full-feature qualification

G1 qualification is in progress. The import adapter suite passes on H2 and
PostgreSQL, including committed-prefix cancellation. The signed Compose
scenario covers scheduled worker dispatch and two exact receipts, migration
dependency calls and preserved history, archive guards, and archive replay.

### Chunked imports

`GET /data/import-capabilities` publishes the supported formats, modes and
limits. The workbench uses these to populate the import form and build the
validation manifest. A saved review can be opened independently. A completely
validated `CHUNKED_UPSERT` review accepts up to10000 rows from a1 MiB source,
within a16 MiB sealed plan. Apply returns202/RUNNING and reserves durable
progress. The scheduled worker dispatches100 rows per transaction using the
original actor's fresh authority. Each chunk commits its rows/history, one head
increment, exact receipt, progress CAS and local outbox together.

| Method and route | Contract |
| --- | --- |
| GET /data/jobs/{id} | Current owned job/progress and original review; reads never dispatch. |
| POST /data/jobs/{id}/advance | Exactly `{expectedRevision:integer}`; advances at most one chunk. A stale progress revision returns current state without another dispatch. |
| POST /data/jobs/{id}/cancel | Empty body or `{}`; preserves already committed rows and receipts. |
| GET /data/jobs/{id}/chunks?afterChunk=-1&limit=20 | Scoped receipt page; limit1..20. |

The saved workbench review displays the committed row count and the pending
or terminal uncommitted count. If reconciliation is needed, it labels the
last confirmed committed count and leaves the current remaining count unknown.
Users can page through committed batch receipts and inspect exact record IDs
and version strings.

Transient commit uncertainty remains recoverable through the same job and
receipt journal. The worker stores a 15-second retry delay for the same job
revision after an unavailable advance. Other eligible jobs can proceed during
that delay.
A deterministic stale target/reference or validation failure
ends FAILED with no committed rows, or PARTIAL with its committed prefix.
Partial/cancelled work is explicitly non-atomic; a changed source or target
requires a newly validated review. No background task impersonates the account
owner or stores a bearer token. V035 resets obsolete pre-production import
control records/artifacts; existing native rows/history/snapshots remain retained.
`NATIVE_IMPORT_WORKER_ENABLED` and `NATIVE_IMPORT_WORKER_DELAY_MS` control the
worker (defaults true/1000ms).

In the table import form, the saved owner review reports examined, valid and
invalid records. Confirmation requires completed validation, and the review
details show the owner digest. Batch progress reports confirmed commits and
the remainder of the validated input. If an apply or cancellation response
fails, the form shows an unknown outcome and offers inspection of the same
saved review. Back to records refreshes the LIVE table query.

When CSV or JSONL exceeds the configured record budget before EOF, the owner
seals a rejected review with `validationComplete=false`, the examined prefix's
`processedRows`, `validRows`, and `invalidRows`, and
`outcomeCode=ROW_LIMIT_REACHED`. `totalRows` and `unprocessedRows` are null.
The rejected review can be inspected again, and applying it changes no rows.

The form maps named file columns to schema fields and asks how to handle
empty strings. For CSV, mapped columns must follow the exact file header
order. The form generates explicit parsers for integers, decimals, booleans,
dates and offset timestamps. For JSONL, it declares the target's wire type.
The JSON mapping editor remains available for custom conversions such as
resolving references or converting units. The owner validates either mapping
through the same import contract.

The saved review returns `typedDiff.mapping.format` and
`typedDiff.mapping.columns` projected from the sealed import document. The
browser uses those values to display the exact mappings and conversion
settings after a reload. It uses current field labels only when the current
schema revision matches the review's target revision.

### Schema migrations and archive

| Method and route | Contract |
| --- | --- |
| POST /data/datasets/{id}/migration-reviews | Exactly `{idempotencyKey,fieldKeys:[...]}`; reserves review/new-field/generation/receipt IDs for15 minutes. Empty fieldKeys supports index-only changes and archive. |
| POST /data/datasets/{id}/migration-reviews/{review}/prepare | Exactly `{schema:<VCL COLLECTION>,mappings:[...],dataMode:"PRESERVE"\|"RESET"}`. Schema revision is exactly current+1. Returns reviewRevision2, exact reviewDigest and impact. |
| GET /data/datasets/{id}/migration-reviews/{review} | Current signed owner inspection and exact saved receipt; no dispatch. |
| POST /data/datasets/{id}/migration-reviews/{review}/apply | Exactly `{reviewRevision:2,reviewDigest,idempotencyKey}`; uses only the sealed stored plan. |
| POST /data/datasets/{id}/archive | Exactly `{reviewId,reviewRevision:2,reviewDigest,idempotencyKey}` for a previously prepared archive review. |

An archive review uses the same reservation and preparation routes, with
`{schema:null,mappings:[],dataMode:"ARCHIVE"}`. Active incoming schema references,
unexpired sealed snapshots, running imports and blocking owner dependencies
prevent archive. Prepared imports/row reviews/other migration reviews are
reported or cancelled with the reviewed archive; retained physical rows and
immutable schemas are not deleted.

The table workbench supports reserving field IDs, editing the migration schema
and mappings as JSON, inspecting impact and confirming the saved review.
The impact panel lists the rule, dashboard and grant dependencies reported by
their owners and identifies blocking entries. The URL retains the review ID
for recovery and the owned response includes the original idempotency key.
An exact confirmation rejected as stale or expired through the review apply
route records a failed outcome. A fresh review is required to proceed.

Each migration mapping is exactly `{targetFieldId,literal:<typed literal>}` or
`{targetFieldId,sourceFieldId,column:<ordinary closed VCL import column>}`.
The column's sourceColumn is the source field UUID; converters, types and units
use the shared compiler/import implementation. Unmapped retained fields copy
their typed values. Every new field needs a reserved ID and explicit fill.
Changing a field's type/unit/reference target requires a new ID and mapping;
the old ID cannot acquire another meaning. Constraints are revalidated for
every row, with complete valid/invalid counts and at most100 value-free errors.
The bounded profile accepts up to10000 existing rows and a16 MiB sealed plan.
RESET explicitly reviews removal of the old current row set and checks actual
incoming row references; it is not an implicit fallback for conversion errors.

Schema/index migrations provision a reserved shadow outside active-data
transactions. The exact READY row set, values, versions, creation timestamps,
optional history and physical indexes are verified before schema/field/index
metadata, generation pointer, result and outbox switch atomically under the
account/head fence. PRESERVE keeps record UUIDs and advances their versions.
Old generations/schema revisions remain retained. Snapshot pages and event-time
lookups resolve the snapshot's own schema and values after current access checks,
without falling back to LIVE. Ordinary description without schemaRevision selects
the current schema; explicit LIVE revisions must match that current schema.

Device-service obtains actual dependency observations from analytics, alert and
dashboard services at
`GET /internal/native-datasets/{id}/dependencies?accountId=...&schemaRevision=...`,
protected by the internal service token. Typed verified compilation dependencies,
actual deployment state, grants/in-flight grant reviews and structural dashboard
query/binding declarations determine impact. Published/active/in-flight effects
block changes; drafts require recompilation. Incomplete scans, corrupt owner
data, mismatched cuts, non-success responses or unavailable owners stop review
or apply. Each owner has an explicit500-row observation bound; overload is not
an empty dependency set. The device client accepts at most256 KiB per response.

Owner cuts are separate observations, renewed after shadow provisioning and
compared to the reviewed vector; there is no global cross-service transaction.
Dependent owners must retain their fresh schema/generation admission checks.
`ANALYTICS_SERVICE_URI`, `ALERT_SERVICE_URI`, `DASHBOARD_SERVICE_URI`,
`INTERNAL_SERVICE_TOKEN` and `NATIVE_DEPENDENCY_TIMEOUT` (default2s) configure
these fixed owner calls. Clients cannot supply replacement owner endpoints or
dependency attestations. Same-key dispatch recovers the same durable receipt;
an uncertain transport result does not authorize a new review/key.

### Historical rows

V038 records capture checkpoints and exact commit manifests alongside the
optional dense history tables. New history starts at an empty creation or a
verified generation baseline. For existing tables the first subsequent write
can establish a baseline from verified current rows. Earlier cuts are unavailable.

| Route under the dataset | Contract |
| --- | --- |
| GET /history/generations | Owner-only discovery of captured generations and their recorded version ranges. Use optional `afterGeneration` and `limit` (1–100). |
| GET /history/generations/{generation}/schema | Owner-only immutable field definitions, schema digest, system record ID field and recorded version range for the specified generation. |
| POST /history/revision | Exactly `{generation,recordId,recordVersion,select}`. The record version is decimal text. The response identifies the captured data version, selected values, recording time and deletion status. |
| POST /history/query | Exactly `{generation,throughDataVersion,document}`. Use a VCL QUERY with its LIVE template and the generation's schema revision. The response manifest identifies the fixed HISTORY cut. |

`GET /data/datasets/archived` lists up to 100 archived tables for the current
account owner. The workbench uses it to open an archived table's history.
History generation discovery, schema, and POST reads also accept the shared
workspace dataset base and `grantId`. Shared schema responses contain only
readable fields and generations of the granted schema revision.
Shared access needs the explicit HISTORY action and is limited to the granted
schema revision, rows and readable fields. The owner can read retained
generations by ID, including after archive while the owner account remains active.
State reads check up to 1,000 cuts, 100,000 checkpoint entries and 10,000
selected row identities within a 16 MiB validation budget. Pages are capped at
512 KiB. An unavailable cut or integrity error fails the request. A cursor
identifies the original cut and current access scope.

Owners can pin a captured cut using `POST /history/leases` with exactly
`{generation,throughDataVersion,durationSeconds,idempotencyKey}`. The lease
lasts 60 to 86,400 seconds, with up to 100 active leases per account. A retry
with the same key and parameters returns the same lease and original expiry.
`DELETE /history/leases/{lease}` releases it. Active leases appear in archive
impact and block dataset archive until release or expiry. Account deletion
releases the account's active leases.

Generation reclamation is opt-in on PostgreSQL through
`NATIVE_GENERATION_RETENTION_ENABLED`. The worker checks up to ten retired
generations per pass after the grace period, which defaults to 30 days
(`NATIVE_GENERATION_RETENTION_GRACE`, minimum one day). It checks active
snapshots and leases again under the account lock before dropping the retired
tables. The deletion and reclamation marker commit together. The worker
preserves schema metadata and excludes active and archived datasets.
Historical reads of reclaimed generations return `HISTORY_GENERATION_UNAVAILABLE`.

### Workspace row and field grants

V037 adds immutable reviewed native access grants. The existing workspace/account
association must already have reviewed consent from the current account owner.
Native grants reuse fresh USER/TEAM membership from the identity service and
the existing canonical device/resource permission owner; workspace roles alone
grant no native rows. The shared actor also needs an active personal account.
The signed Compose scenario verifies scoped user reads and revocation. It
checks that the recipient cannot discover or project a real field outside the
grant, including when the owner can read a migrated value for that field.

Owner routes use the ordinary signed actor and current owned account:

| Method and route | Contract |
| --- | --- |
| POST /data/datasets/{id}/sharing-reviews | Exactly `{idempotencyKey,definition:{workspaceId,subject:{kind:"USER"\|"TEAM",id},policy}}`; prepares15-minute consent and reserves grant/receipt IDs. |
| GET /data/datasets/{id}/sharing-reviews/{review} | Read the saved definition, digest and result under fresh account ownership. |
| POST /data/datasets/{id}/sharing-reviews/{review}/apply | Exactly `{reviewRevision:1,reviewDigest,idempotencyKey}`; refreshes membership, association, schema/generation/head and requested row identities. |
| GET /data/datasets/{id}/sharing | Bounded active grants for the owner, up to1000. |
| POST /data/datasets/{id}/sharing/{grant}/revoke | Exactly `{expectedRevision:1,idempotencyKey}`; subtractive owner revocation remains available without workspace/identity I/O. Same-key retry returns the exact saved receipt. |

Policy is exactly `{readFieldIds:[UUID...],writeFieldIds:[UUID...],rows:{mode:"ALL"\|"IDS",recordIds:[UUID...]},actions:[...]}`.

The table workbench lets an owner review grants by workspace and recipient ID,
select fields and actions, and revoke active grants. The saved review ID remains
in the URL and its owned response provides the original idempotency key.
There are1..64 readable fields, at most64 writable fields and at most500 explicit
record IDs. Writable fields must also be readable. READ is required. HISTORY grants access to retained row versions. INSERT,
UPDATE and DELETE are separate reviewed actions. INSERT requires ALL rows,
write access to business keys and every required field. ALL has no explicit IDs.
IDS may be empty and permits only those stable row UUIDs. Each query uses one
explicit grant as a coherent field/row/action scope; combining fields from one
grant with the row set from another is not supported.

Shared routes have the base
`/data/workspaces/{workspace}/accounts/{account}/datasets/{id}` and require
`?grantId={grant}`. Signed identity supplies the actual actor. The supplied account,
workspace and grant IDs are selectors, never authority or an owner impersonation.

`GET /data/workspaces/{workspace}/datasets` lists active grants for the current
member or their teams. It returns table labels and the selectors needed to open
each table, with optional `afterGrant` and `limit` (1–100). The workbench uses
this route to browse shared records in a workspace. Reads still resolve the
selected grant and its field and row scope.

The shared table description returns `keysVisible` and includes `keyFieldIds`
only when the grant permits every business key. The workbench offers the
allowed row operations when it can address a record and routes changes through
the shared review and confirmation endpoints.
The workbench retains the review and grant selectors in the URL for recovery.
Reopening it performs a fresh scoped description and review read. Confirmation
uses the original saved document key.

| Method and suffix | Contract |
| --- | --- |
| GET base | Scoped descriptor: only readable field definitions, the system row ID, granted policy, scope digest and query capabilities. |
| POST /query | Ordinary closed `{document:<VCL QUERY>}` with authorized fields in projection/filter/order. |
| POST /query-snapshots | Seal the same scoped query; ordinary snapshot limits apply. |
| POST /query-asof-snapshots, /query-asof-manifest, /query-asof, /query-asof-split | Same closed request forms as the ordinary native routes, over the authorized sealed selection. |
| POST /mutation-reservations | Exactly `{schemaRevision,idempotencyKey}`; requires a current write action and reserves the ordinary row review/mutation/record/receipt IDs. |
| POST /mutation-reviews | Exactly `{reservationId,document:<VCL MUTATION>}`; uses the existing compiler and reviewed row engine. |
| GET /reviews/{review} | Current scoped review/result; scope changes do not expose the old document. |
| POST /reviews/{review}/apply | Ordinary `{reviewRevision,reviewDigest,idempotencyKey}`. |
| POST /reviews/{review}/reconcile | Empty body or `{}`; inspect only, without dispatch. |

The compiler receives only readable field metadata, including for predicates
and ordering. SQL appends the grant's row predicate before seeking and limiting;
hidden rows do not contribute pages, row counts or temporal cardinality.
Snapshot sealing captures that selection. Cursor, review and snapshot scopes
bind the actual actor, grant/revision/policy, workspace membership/team version,
active association version and active actor/account state. Fresh identity and
local grant/association checks surround operations; no saved JWT or thread-local
owner context supplies permission.

Shared row writes recheck action and field scope, then actual row membership
before CAS can expose an ungranted identity. Direct references to other native
rows require a current matching native read grant. DEVICE/SOURCE references
require current canonical device read permission from the existing workspace
resource owner, with membership and association cuts checked against the native
transaction. Every reference still undergoes normal type/account/existence
validation. Permission snapshots are renewed around operations; they do not
claim a cross-service transaction with the identity owner.

Owner writes also accept `REF(LOCATION)`, `REF(VARIABLE)`, `REF(SCHEDULE)`,
`REF(OPERATIONAL_FACT)` and `REF(SPATIAL_FEATURE)`. These resolve against
canonical records of the owning account. A reference stores the identity.
Consumers that need geometry, schedule or fact contents must request their
own authorized revision. Shared writes currently accept native dataset,
device and source references under their respective grants.

The table creation form displays reference targets from the published VCL
registry and the current owner's table list. A self reference uses the table
ID assigned by the reservation.
The workbench also creates and edits time intervals and WGS84 points.
An interval takes UTC timestamps with an optional end. A point takes longitude
and latitude. Neither type is selectable as a business key or a scalar index.

Grant creation/revocation changes the association epoch, invalidating stale
cursors, reviews and snapshots. Account archive revokes grants and cancels pending
sharing reviews. Active grants are included as blocking native migration/archive
dependencies; an owner must revoke them explicitly before changing the schema.
Metadata/import/schema administration remains owner-only. Public dashboard
sharing creates no native permission, and these routes do not publish data or
activate RULEs.

## Bounded atomic imports

The `native-atomic-import-1` profile uses the ordinary signed actor and current
account owner fence. The table workbench supports file upload, JSON column
mappings, validation, confirmation and saved review recovery. V033 stores
immutable uploaded artifacts and owner-sealed validation plans separately from
the dense target rows. Public routes are:

| Method and route | Contract |
| --- | --- |
| POST /data/import-artifacts | `application/octet-stream`, required `Idempotency-Key`; returns `{id,sha256,bytes,expiresAt}`. Read is bounded before body allocation; at most 1 MiB. |
| POST /data/datasets/{id}/import-reviews | Exactly `{idempotencyKey,document}` with an ordinary VCL `IMPORT/1` document. Returns an immutable review and typed change counts. |
| GET /data/datasets/{id}/import-reviews/{reviewId} | Fresh signed account authority; reads the saved validation/result without dispatching writes. |
| POST /data/datasets/{id}/import-reviews/{reviewId}/apply | Exactly `{reviewRevision:1,reviewDigest,idempotencyKey}`. The key must equal the validation key. |

`ATOMIC_INSERT`, `ATOMIC_UPSERT` and the generation-replacement profile below use
declared `maxRows <= 500`, `maxBytes <= 1048576`, and a canonical plan of at most
16 MiB in the current sealed-plan profile (source uploads remain 1 MiB).
The target must have declared nonnullable business keys in their exact
schema order. All data uses the existing typed dense row/CAS/history path.
Uploads expire after 30 minutes; prepared reviews expire after 15 minutes.
There are at most 100 unexpired artifacts and 100 active imports per account.
The import quota covers unexpired prepared reviews and running chunked jobs.
Completed, rejected, expired and cancelled reviews remain available for
inspection without occupying an active slot. Upload lazily removes
that account's expired raw artifacts; account deletion removes all raw artifacts
and cancels prepared jobs. No background retention worker is claimed.

CSV uses the document's delimiter and exact header, doubled quote escaping,
quoted newlines and UTF-8 BOM at the beginning. JSONL requires one exact object
per line, rejects duplicate keys, unknown/missing columns and trailing input.
Invalid UTF-8, malformed structure or exhausted file/row limits fails validation
without a prepared review or target writes. Row conversion errors produce a
`REJECTED` review with exact `totalRows/validRows/invalidRows`,
`validationComplete=true`, at most 100 value-free errors, and an explicit
`displayedErrorsTruncated` flag. No rejected review can be applied.

Implemented conversions are identity, parse_int64, parse_decimal, parse_boolean,
parse_date, parse_instant, parse_duration, exact convert_unit and owner-batched
resolve_ref. Source types
and units are explicit. CSV text carrying a unit retains that mapping unit
through numeric parsing. Decimal rounding is explicit; inexact unit division
fails instead of rounding silently. Local instants reject DST gaps and honor
the declared overlap policy. Duration input is numeric text in the grammar's
explicit fixed source unit. Empty, NULL and omission remain distinct.
`CHUNKED_UPSERT` uses the durable progress contract below. External imports fail
explicitly as unsupported before any target write.

Validation seals the artifact hash, document/mapping/schema, generation/head,
scope digest, exact target row IDs/versions and canonical typed mutation plan.
The review's `typedDiff` exposes the target/precondition, artifact ID/hash,
mapping revision, inserts, updates and zero deletes. Counts on rejected reviews
describe valid candidate rows only; `invalidRows` still blocks the whole batch.
The owned review includes the original idempotency key in `typedDiff` for
confirmation after reload.
Apply checks the exact review digest/key, current access, expiry and head before
rechecking every typed mutation and referenced resource. Target changes return
`VALIDATION_STALE`/409 and require a new validation. Updates retain their record
UUIDs; omitted nullable columns retain existing values, explicit NULL clears.

All rows, selected history entries, one head-version increment, saved result and
one `NATIVE_ROWS_IMPORTED` local outbox intent commit in one transaction. The
saved result is the batch receipt and includes every record ID/version.
Concurrent applications of the same review return that same receipt. An outbox
failure rolls back the entire batch. A lost response is inspected by GET or
replayed with the same review/digest/key; no new job is needed. This local
transaction does not claim cross-service atomicity or an activated RULE runtime.

## Reviewed generation replacement

`STAGED_REPLACE` uses the same upload/review/inspect/apply routes with the
`native-generation-replace-1` profile. Source and existing target selections are
each bounded to 500 rows; the declared source byte/row limits and canonical plan
limit above still apply. A larger current target fails `REPLACEMENT_TARGET_LIMIT`
before preparing a replacement. This profile replaces the whole dataset's row
set. Optional fields omitted from the mapping become NULL in the new generation;
ordinary UPSERT still preserves omitted columns.

Validation seals the complete desired row set, old generation/dataVersion,
original row versions and created timestamps, stable record IDs for matching
business keys, new server-reserved record IDs, and the exact removed row IDs and
versions. `typedDiff` adds `generation`, `removed` and the exact deletion count.
An empty source can prepare a reviewed empty generation only after showing the
complete deletion impact. It never deletes rows during upload or validation.
Incoming native references are checked by bounded indexed batches; surviving
self-references are checked against the final planned rows. Unindexed incoming
references fail `INDEX_REQUIRED`; referenced removals fail `ROW_REFERENCED`.

After exact confirmation, a durable generation journal owns a shadow table and
optional dense history table with separate trusted physical/index identities.
DDL executes outside the active data transaction, including on H2 where DDL
commits implicitly. Population and READY commit together. READY stages are
verified against every planned row, typed value, key, version, original created
timestamp and selected history entry; they are never refilled or recreated on
retry after corruption/loss. Interrupted pre-READY provisioning can reconcile
the same reserved physical identity. Ordinary readers cannot select a shadow
table by its physical ID.

Cutover holds the same account/dataset fences used by ordinary row writers.
It rechecks the captured head and exact old row identities/versions, current
reference guards, and actual shadow contents. It then atomically switches the
logical dataset's generation/physical pointer, advances dataVersion once,
retires the old journal entry, activates the new one, updates index metadata,
stores deletion history tombstones when selected, and commits the exact batch
receipt and one `NATIVE_GENERATION_REPLACED` local outbox intent.
Concurrent same-review applications return one receipt. An outbox failure keeps
the old pointer and leaves the verified stage available for the same-key retry.
A writer that commits first invalidates the stage; a review for the retired head
cannot write after the pointer switch.

Old physical generations remain retained. Existing sealed query snapshots keep
their original contents/generation and fresh access checks; LIVE queries and new
row reviews refresh the current pointer, while old LIVE cursors fail on generation
change. The owner verifies the active pointer against its account-scoped journal
and actual catalog; drift fails `NATIVE_CATALOG_DRIFT`/503. Historical table-query
capability, generation garbage collection/retention leases, schema migration and
external database replacement are not claimed by this profile.

With the Compose application profile running, execute
`python scripts/native-import-docker-smoke.py --summary artifacts/native-import-smoke-summary.json`.
The script uses a separate synthetic account and ordinary signed gateway APIs;
it checks atomic insert, exact replacement review/receipt replay, stable IDs,
retained sealed snapshots, stale-import rejection and key-only UPSERT readback.
It makes no model calls. The ignored summary records fixture IDs, never credentials.

## Reference resolution during import

`resolve_ref` supports the document's exact authorized native target/revision,
one declared unique key field and `EXACT_ONE` cardinality. It works at each
declared position in the ordered conversion chain, including REF-key lookups
after another `resolve_ref`. No first/nearest match or cross-account lookup is
allowed. Missing identities produce value-free `REF_NOT_FOUND` row errors;
unqualified/mismatched key or target types fail before writes. Explicit NULL for
a nullable target remains NULL rather than an invented record ID.

The owner deduplicates lookup keys and executes batches of at most 500 keys per
table and conversion round. The profile caps reference owners at32 and distinct
lookup keys at2048; these are resource limits, not a throughput claim. The
resolver selects only key/identity/version columns. Its checked results also
supply mutation reference authorization, avoiding per-row authorization queries
for those already resolved IDs. Direct REF values still use normal owner checks.

The sealed plan retains every consulted schema digest/revision, generation and
dataVersion, exact key tuple, resolved record ID and string-encoded row version.
Declared owner metadata is pinned even when every supplied value is NULL.
`typedDiff.referenceReadSet` exposes the owner cuts and resolved-record counts,
without raw lookup keys. Apply and each shadow/cutover phase recheck actual owner
heads and batch-read exact identities/versions. Any changed cut returns
`VALIDATION_STALE`/409 and requires new validation, even if a matching ID still
exists. The original review digest is recomputed against the document, plan,
actor, scope and expiry; changing a stored plan and its checksum cannot reuse
the old approval. Existing current-operation snapshots remain independent.

## AI native table authoring

`app.ai.orchestration.native-data-enabled` defaults to `false`. When explicitly
enabled and the signed owner's language profile matches, the existing assistant
catalogue exposes `CREATE_NATIVE_TABLE`. It supports new tables with TEXT, BOOLEAN,
INT64, DECIMAL, DATE, INSTANT and DURATION fields, registered numeric units, business
keys, indexes and optional history capture. TEXT uses maxLength120 and DECIMAL uses
precision18/scale3, matching the manual form. History capture does not enable
historical queries. Rows, schema edits, imports, references, maps and RULEs are outside
this intent.

The server reserves real IDs and prepares the ordinary owner review; the model
cannot supply SQL, identities or consent. The AI review displays the same schema
preview as manual creation. Its expiry cannot exceed the owner lease. Explicit
confirmation applies the saved owner revision/digest/key. SUCCESS requires the
original successful receipt and exact dataset schema readback; the resulting link
opens `/data?datasetId=<actual-id>`. UNKNOWN keeps the original review, never a fresh
reservation. After the AI review expires, UNKNOWN recovery is GET-only receipt and
resource inspection; unresolved outcomes remain UNKNOWN. This slice remains
unqualified for live model quality and broader cross-domain composition.

## Access and routes

Public routes go through the protected gateway /data/** route. device-service
verifies the signed actor and its current ACTIVE account; actor/account/role
cannot be supplied in a JSON body. A read does not provision a missing account.
This slice supports the owning personal account; workspace grants do not
implicitly grant access to collections. Unknown/foreign datasets/reviews return
the same unavailable response, without foreign counts.

| Route | Contract |
| --- | --- |
| GET /data/datasets | All READY native table summaries `{id,schemaRevision,label}` for the current owner, bounded by the100-table account quota; no row counts or foreign metadata |
| GET /data/language | Authenticated publication of `{registry,nativeQuery,ruleExecutionReady:false}`; registry is generated by common-vcl, including registryDigest and closed schema definitions |
| GET /data/source-bindings/{id}?schemaRevision=1 | Signed source-owner schema snapshot; omitting revision selects the current schema; capture and RULE readiness remain explicitly unavailable |
| POST /data/source-bindings/{id}/observations | Bounded exact-generation/checkpoint accepted-journal prefix; signed current source/account authority is renewed before and after telemetry RPC |
| POST /data/review-reservations | Exactly `{kind:"COLLECTION",fieldKeys:[...],idempotencyKey}`; returns reviewId, resourceId, fieldIdsByKey, expiry and reserved receiptId |
| POST /data/review-reservations | Exactly `{kind:"MUTATION",target:{datasetId,schemaRevision},idempotencyKey}`; returns reviewId, mutationId, createdRecordId, receiptId and expiry |
| POST /data/dataset-reviews | Exactly `{reservationId,document:<VCL COLLECTION>}`; returns revision, reviewDigest, semanticDigest and typedDiff |
| POST /data/datasets/{id}/mutation-reviews | Exactly `{reservationId,document:<VCL MUTATION>}`; prepares the reserved row intent against current owner metadata |
| POST /data/datasets/{id}/query | Exactly `{document:<VCL QUERY>}`; returns rows, nextCursor, readManifest, completeness and truncationReason |
| GET /data/reviews/{id} | Current owned reservation/review/outcome |
| POST /data/reviews/{id}/apply | Exactly `{reviewRevision,reviewDigest,idempotencyKey}`; uses only the stored document |
| POST /data/reviews/{id}/reconcile | Empty body or `{}`; reconcile the same dispatch/receipt |
| GET /data/datasets/{id}?schemaRevision=1 | Pinned native metadata, generation/dataVersion, access-scope and schema digests, actual ready indexes and capability flags |

All request envelopes reject unknown/duplicate fields, malformed UTF-8 and unsafe
JSON numbers before effects. Field keys are 1..64 distinct ASCII technical tokens
matching `[A-Za-z][A-Za-z0-9_]{0,63}`, not display names. Idempotency keys contain
1..160 Unicode code points, cannot be blank or contain NUL. Resource/field IDs
come from the reservation; prepare rejects any substituted or missing reserved ID.

The internal GET /internal/data/datasets/{id}/schemas/{revision}?userId=... route
requires the existing internal-service token and rechecks that actor's current
account. It is not exposed by the gateway. The response uses common-vcl's
VclDatasetDescription; the local NativeDatasetMetadata adapter resolves actual
owner SQL metadata for the common compiler, without caller-supplied fixtures.

## Review, dispatch and recovery

Reservations expire after 15 minutes. Same-key reservation replay preserves IDs
and the original expiry; it does not renew authority. First preparation binds
the exact canonical document, actor/account scope, generation, system record-key
ID, receipt ID and expiry into reviewDigest. A changed preview advances revision
and invalidates the previous digest. Only revision1 native schema creation is
accepted by creation; later revisions use the separate reviewed migration workflow.

Apply authenticates the actor, locks its current account and review and checks
the exact revision/digest and current scope before new dispatch. There is no
approved:true switch. APPLYING and an UNKNOWN receipt are committed before DDL.
Possible dispatch failure stays UNKNOWN; HTTP202 includes the same receipt ID and
Retry-After. A transport/storage error is not proof of rollback: inspect/reconcile
the known review, never invent a new key as recovery.

Provisioning is replayed only against the reserved dataset/generation/schema.
Reconciliation first inspects an existing READY result. After expiry or a changed
scope it cannot authorize fresh DDL, but may finalize a verified committed result.
SUCCEEDED replay returns the same receipt. Completion verifies actual resource,
generation, schema and system-key identity, and commits the result receipt,
review outcome and one local COLLECTION_READY outbox intent together. This
outbox is not currently published to Kafka; no downstream delivery is claimed.

## Storage and readiness

V024/V025/V026 create cfg_dataset, cfg_schema_revision, cfg_field, cfg_index_manifest,
cfg_collection_review, cfg_mutation_receipt, cfg_row_review, cfg_row_receipt and
cfg_outbox. Collection and row receipts have separate typed review foreign keys.
cfg_field contains
metadata, not row values. Logical revisions and canonical schema digests are
immutable through this API. The provisioning journal remains inspectable across
process restart; a ready schema cannot be overwritten using another definition.

NativeSqlLayout generates cfg_data_<uuidhex> and f_<uuidhex> identifiers from
UUIDs, with allowlisted types. Labels, SQL text and user column names never enter
DDL. Each dense table contains record ID/version, optional business-key digest,
timestamps and actual typed scalar columns. POINT and INTERVAL use bounded
paired columns. Declared history creates an optional dense cfg_hist_<uuidhex>
table; INSERT/UPDATE capture the committed row and DELETE appends the previous
values with the next version and deleted=true. Historical querying remains pending.

Only primary/business identity and selected field indexes are provisioned.
Current index support excludes POINT/INTERVAL and TEXT with maxLength>512.
Limits are 100 collections per account, 64 logical fields, 8 selected indexes and
100 outstanding reviews per kind. PostgreSQL transactions set 2s lock/10s statement
timeouts. Actual column types/precision/scale/nullability, primary/unique indexes,
selected indexes and PostgreSQL index validity are checked before READY.
A missing/corrupt READY table or index fails NATIVE_CATALOG_DRIFT; retry does not
silently recreate an empty table. H2 is functional evidence, not a claim of
transactional DDL or PostgreSQL performance.

Account deletion flushes the DELETED authority row before archiving native
datasets/cancelling unfinished reviews in the same owner transaction. Native jobs
fence against that account row. Evidence is retained, as in the existing soft
account lifecycle; reactivation does not silently unarchive collections.

## Reviewed single-row writes

MUTATION reservation, prepare and apply use the same idempotency key. The server
reserves mutationId, createdRecordId and receiptId; clients cannot substitute them.
Prepare stores the common compiler's canonical document and binds actor/account,
scope, review revision, expiry and reserved IDs. A changed document invalidates
the old review digest. Apply accepts only the stored document, never a replacement
payload or an approved flag. The dataset ID in the route must match its target.

Business keys are the complete declared immutable ordered tuple. A SHA-256 digest
locates the unique tuple, and the actual typed tuple is compared before writing.
NATIVE TEXT keys use NFC; numeric keys retain exact values and schema units.
Collections without business keys receive a server record UUID on INSERT;
UPDATE/DELETE use the described system recordIdFieldId as a REF key. System IDs
and business keys cannot be assigned through values. Native references are
authorized at preparation and again at commit. Bounded indexed incoming DATASET
references block row deletion. The sealed event-time lookup below documents
cardinality and interval rules. Canonical DEVICE/SOURCE archive guards remain
outside the verified native row deletion contract.

Each new write locks the ACTIVE owner account and READY dataset head, rechecks
the stored generation/dataVersion and expectedRowVersion, and commits one row,
optional history, monotone dataVersion increment, exact receipt, SUCCEEDED review
and NATIVE_ROW_MUTATED local outbox intent in one transaction. UPDATE omission
preserves a field; explicit NULL clears a nullable field. Decimal precision/scale,
INT64, UTC microsecond timestamps, enum membership, POINT/INTERVAL paired columns
and SQL nulls cross an exact typed codec. NUL in native TEXT/ENUM is rejected
before dispatch, avoiding PostgreSQL/H2 disagreement. No value EAV is introduced.

The APPLYING marker is committed before the write transaction. A lost commit
acknowledgement cannot overwrite a committed SUCCEEDED receipt. Reapplying the
same reviewed intent is serialized and returns the original result. Uncertain
transport failures remain UNKNOWN; reconcile only reads durable evidence and
does not dispatch. Reapply of APPLYING/UNKNOWN may execute the same stored intent
only under its original unexpired lease and fresh scope. Expiry never extends
authority. FAILED is terminal and returns HTTP409 with outcomeCode in the review
view; APPLYING/UNKNOWN return HTTP202 and Retry-After:2. Successful views include
result:{mutationId,datasetId,recordId,recordVersion,dataVersion,generation,
schemaRevision,deleted}; recordVersion is an exact decimal string.

## Bounded LIVE query profile

QUERY uses actual owner metadata and the common compiler. Profile native-live-sql-1
supports projection of all native types, limit1..100 and exactly one ASC/DESC
order field: the described system record ID. SQL predicates support eq/ne/lt/le/
gt/ge/and/or/not/is_null/is_known over BOOLEAN, INT64, DECIMAL, DATE, INSTANT and
DURATION. Predicate field access requires a declared ready index. is_known accepts
only FIELD/CONST, so it cannot hide an unsupported or failing nested expression.
Indexed TEXT and ENUM support equality, inequality and null checks. Indexed REF
fields support equality and inequality with another compatible field and null
checks. The profile publishes these types separately as equality-only and
reports that reference constants are unsupported. Point and interval
comparisons, other ordering and arithmetic return typed unsupported errors.
Each SQL read has a 2s
query timeout; expiry produces QUERY_BUDGET_EXCEEDED. Performance acceptance at
production volume remains open.

The owner and shared workbenches offer a filter over the readable ready
indexes and the published SQL operations. A new filter starts a new traversal.

Rows contain recordId, exact-string recordVersion and field-ID→typed-literal
values. A page reads limit+1 rows to distinguish PAGE_LIMIT from exhaustion.
The signed opaque seek cursor binds actor/account, scope, dataset/schema,
generation, canonical query digest (selection/filter/order/limit), record position,
initial dataVersion and an absolute 10-minute expiry. Advancing does not renew it.
Tampering, changed query/scope/generation and expiry fail explicitly. Configure
NATIVE_DATA_CURSOR_KEY to the same Base64-encoded key of at least32 bytes on
replicas; without it a process-local random key invalidates cursors on restart.

readManifest contains datasetId, schemaRevision, generation, current dataVersion,
schemaDigest, accessScopeDigest, queryDigest and consistency=LIVE. Intermediate
pages are PARTIAL/PAGE_LIMIT. Exhaustion is COMPLETE only if dataVersion stayed
unchanged since the first page; otherwise it is UNKNOWN/LIVE_DATA_CHANGED. LIVE
does not promise a frozen traversal or temporal evidence coverage.

## Sealed query snapshots

Send a signed `POST /data/datasets/{id}/query-snapshots` with
`{ "document": <VCL QUERY> }`, using `read: { "mode": "LIVE" }` and
`cursor: null` in the template. The owner uses the same typed selection,
indexed predicates and ordering as LIVE. It captures the entire matching result
in one fenced transaction and returns `snapshotId`, `expiresAt` and `rowCount`.
The seal fails with `SNAPSHOT_BUDGET_EXCEEDED` above 256 rows or 512 KiB of
canonical row bytes. An account may hold up to eight active snapshots.

To page the snapshot, send the same QUERY to `POST /data/datasets/{id}/query`
with `read: { "mode": "SNAPSHOT", "snapshotId": "<id>" }`. Keep projection,
filter, order and page size the same and use the returned cursor for later
pages. The read manifest carries the snapshot ID, captured generation and data
version. Pages stay stable across source writes and recheck account access,
scope and schema. Cursor expiry uses the original ten-minute seal lifetime.
Expired snapshots fail with `SNAPSHOT_EXPIRED` until reclaimed on a later seal.
Reclaimed or foreign snapshots return `SNAPSHOT_UNAVAILABLE` for callers with
dataset access. Account deletion removes the materialized rows. Snapshot
completeness applies to the sealed selection at its captured version.

### Event-time lookup within a sealed selection

The table workbench can capture a snapshot and look up a record at an event
time or split a requested interval. It displays the captured data version
and reports gaps, overlapping matches and unknown intervals individually.

Create the index with a signed `POST /data/datasets/{id}/query-asof-snapshots`.
Use the same LIVE template as for a plain seal and include `intervalFieldId`
and `keyFieldIds`. The owner builds the index in the seal transaction.

Send a signed `POST /data/datasets/{id}/query-asof` with the same SNAPSHOT
QUERY in `document`, `cursor: null`, an `intervalFieldId`, an ISO UTC
`eventTime` at microsecond precision and a `keys` array of one to eight
`{ "fieldId": "<id>", "literal": <typed VCL literal> }` pairs. The interval
and key fields must match the sealed projection. The lookup checks
typed key equality and half-open intervals. It returns `KNOWN` with the
selected row only for an exact match. No match, overlap or an unknown
interval on a matching row returns `UNKNOWN` with a reason and no row.

The recorded cutoff is the snapshot's captured data version. A correction
visible in a later snapshot leaves earlier results intact. This path works
over the bounded materialized selection and uses the snapshot/key/time index
to identify candidates.

For a finite interval, use signed `POST /data/datasets/{id}/query-asof-split`
with the same query, interval field and keys, replacing `eventTime` with
`start` and `end`. The owner returns ordered, contiguous half-open segments
covering the requested interval and one common snapshot manifest. Gaps,
overlaps and unknown effective intervals carry explicit UNKNOWN outcomes.
The response budget applies to the whole split, so oversized reads fail
without returning a partial timeline. The analytics adapter checks the
snapshot manifest and segment coverage before accepting either lookup mode.

Signed `POST /data/datasets/{id}/query-asof-manifest` verifies an existing
SNAPSHOT query with its `intervalFieldId` and `keyFieldIds`. It returns the
same read manifest without returning rows. Analytics uses it to prepare
each declared lookup node and binds the manifest to the compiled scope.
It checks the grant before and after lookup reads.

## Manual native workbench

The authenticated /data console route is available as **Data tables** in ordinary
and administrator navigation. It lists the current owner's tables, describes a
selected immutable schema and loads25-row LIVE pages. No row totals are invented;
UNKNOWN after a changed traversal is shown separately from empty or exhausted
pages. Obsolete table/page reads are aborted and cannot replace newer selections.

Table creation offers Text, Boolean, Whole number, Decimal, Date, UTC timestamp,
Duration (integer microseconds), Choice, Reference, Coordinates and Time interval.
The Choice editor accepts up to 64 distinct values, entered one per line, and
the saved preview displays those values. Available types intersect the published
registry with the available form renderers. The form uses TEXT120 and
DECIMAL(18,3), optional nullable fields, ordered business-key components, selected
indexes and row-revision capture. IDs come only from the reservation. The saved
owner preview displays fields, units, requiredness, keys, indexes and history
before an explicit confirmation applies its exact stored revision/digest.

The default-off assistant table creation workflow also accepts all eleven
field types. It uses the same VCL field definitions, renders the same saved
preview, and resolves a self-reference against the reserved table UUID.

Row editing uses an allowed-value selector for ENUM, a target row or resource
UUID for REF, a longitude/latitude pair for POINT and UTC endpoints for INTERVAL.
For table references in the owned row editor, Browse records lists authorized
LIVE rows from the declared target table, using business-key values when
available and preserving the selected row UUID in the reviewed mutation.
For DEVICE and LOCATION references in the owned row editor, the picker
searches the existing authenticated resource lists and fills the selected
UUID. Decommissioned devices are excluded. The owner validates the reference
again when it prepares and applies the row mutation.
SOURCE, VARIABLE, SCHEDULE and OPERATIONAL_FACT references can be selected
from the corresponding device owner lists. Variables use the chosen device's
type. Operational event selection uses an explicit bounded UTC date range.
For POINT fields, Choose on map fills the same longitude and latitude inputs
used for manual entry. Both paths prepare the same typed POINT mutation.
For SPATIAL_FEATURE references, the owned row editor lets the user select a
circle from the same catalog used by Manage circle zones. The saved reference
is the feature UUID. GET /data/spatial-features/circles returns current owned
circle revisions as `{items,nextAfter}`. Use the returned UUID as `after`
to read the next page. The default limit is 25 and the maximum is 100.
Updates distinguish
Unchanged from Set value and Clear to empty. Keys stay immutable. INT64,
DECIMAL, DURATION and row versions remain strings through forms/JSON. No Number
conversion or guessed unit conversion occurs. Delete is independently reviewed.

The reviewId remains in the URL through reservation/prepare/apply and reload.
Reload inspects the owned review without executing. A lost response disables
confirmation until the existing outcome is inspected. UNKNOWN/APPLYING expose
only reconciliation of the original review; no new recovery key or automatic
redispatch is generated. A restored PREPARED row uses its stored idempotency key;
a restored PREPARED collection may choose its apply key before dispatch. Server
expiry and authorization remain authoritative. No raw draft or row data is saved
to local storage. Exact receipt and review digests remain available in details.

This is a native UI increment, not complete UI/AI parity. AI tool registration,
imports/external modes, maps/relations, rule authoring/activation and full M1
acceptance are not qualified. Rule bundles still require real stream bindings,
execution grants and other G2/G4 owners; native row readiness cannot substitute
for them.

## Verification and remaining gates

The G1 functional Compose scenario passes with Postgres 16 and Flyway V041.
It covers approved imports and replay, revision migration with dependency cuts,
preserved historical reads, archive and workspace field/row scope. The complete
Java 21 reactor passed 2,051 tests across 16 modules before the catalog hot-path
optimization. The focused reruns and the signed Compose replay on the optimized
implementation passed. See the
[G1 task evidence](../../../openspec/changes/archive/2026-11-17-define-configurable-domain-data/tasks.md).

NativeCatalogH2Test/NativeCatalogPostgresTest verify actual typed tables, optional
history allocation, selected indexes, restart identity, scope rejection, catalog
drift, compiler metadata, assignment bounds and expiry. NativeCollectionsApiTest
and NativeCollectionsPostgresApiTest exercise signed HTTP review/apply/describe,
stale confirmation, foreign scope, lost completion/reconciliation and deletion,
plus reviewed system-key rows, queries and lost row commit acknowledgements.
NativeRowsH2Test/NativeRowsPostgresTest exercise exact typed round trips,
nullable updates/history/tombstones, conflicting writers and same-intent races,
atomic rollback including outbox, business/system identities, generation/row CAS,
reference deletion/commit guards, indexed predicates and signed seek leases.

Run `mvn -pl services/device-service -am test` from backend. Docker PostgreSQL16
is required for the PostgreSQL contracts. Gateway tests cover route loading and
existing identity guards. Platform acceptance and the PostgreSQL latency gate
require their own evidence.

Earlier single-row checkpoint: the full backend reactor passed 1105 tests with no
failures/errors/skips, including32 new cases in this increment. The final16-case
signed API run also passes after the account-deletion row-review assertion.
Structure, OpenSpec104/104 and VCL documentation checks pass. Ignored logs:
artifacts/g1-native-rows-backend-tests.log and g1-native-rows-final-api.log.

Native workbench verification:199 focused backend tests pass, including signed
H2/PostgreSQL list isolation and byte-equivalent shared registry publication.
Frontend135 files/424 cases pass with two workers; the earlier parallel run had
one existing ML workflow timeout, and all23 cases in that file also pass alone.
Ten new native tests cover reservation/review confirmation, exact numeric strings,
NULL/omission, stale-response isolation, pagination and recovery without dispatch.
Typecheck and production build pass. A disposable visual fixture checks the actual
components on desktop and at390px; it is distinct from signed owner API evidence
and does not qualify end-to-end AI or rule execution. Fixture files/server were
removed after inspection. Logs remain ignored in artifacts/native-workbench-*.

## Source binding schema journal

`GET /data/source-bindings/{id}` describes an existing canonical data-source UUID;
it does not create another source or replace numeric variable bindings. The
optional positive `schemaRevision` selects an immutable historical schema.
The owner returns `id,accountId,actorId,deviceId,schemaRevision,currentSchemaRevision,
snapshotId,definition,definitionDigest,accessScopeDigest,sourceStateDigest,
sourceActive,ruleExecutionReady,captureCapabilities`. Account and actor are resolved
from signed identity and current SQL authority. Native dataset and source binding
descriptions use the same owner scope digest.

V027 stores `cfg_source_field_identity` and `cfg_source_binding_snapshot`. Source
configuration writes publish metadata in their existing transaction, independently
of the Kafka publish flag. A source row lock serializes revisions; identical
stored configuration representation reuses the previous snapshot. Legacy mapping
and adapter JSON are pinned as stored text inside the fingerprint, preserving
numeric distinctions beyond the JCS/IEEE754 safe integer range. A changed stored
representation conservatively creates a new revision. Field UUIDs are assigned by the owner
and retained by source/key across removal and readdition. Configuration changes,
including processing-bundle changes, produce a new immutable revision and snapshot
UUID. Last-seen telemetry updates do not create schema revisions. A snapshot UUID
identifies metadata, not a captured input generation.

Profile `legacy-source-schema-1` projects only explicitly retained telemetry-policy
fields. Boolean and bounded text fields have nullable VCL types. Legacy numeric
fields remain in `fieldMappings`/`unsupportedFields`: registered units require an
explicit exact numeric conversion, and other units require a unit mapping. No
unit, decimal precision, event-time, subject or coverage is guessed. Variable
processing pins invalidate the schema fingerprint; numeric variable outputs are
not advertised as exact VCL inputs by this profile. Adapter configuration contributes
only to a fingerprint; raw configuration, hosts, credential references and tokens
are not copied into the binding definition.

An authorized source configuration can declare `mapping.sampleHold` with
`version: "bounded-sample-hold-1"` and a positive decimal-string
`maxHoldUs`. Device Service validates it and copies it into the immutable
binding definition. Analytics limits positive-duration witnesses to the
smaller of this value and the rule's coverage gap. The contract alone
does not establish event-time coverage.

A retained telemetry field can explicitly declare type `instant` with unit
marker `1`. The source accepts canonical UTC instant strings at microsecond
precision and publishes the field as VCL `INSTANT`. This lets a source
carry interval bounds separately from the journal event timestamp.

A dedicated interval source can declare `mapping.intervalReport` with
`version: "interval-end-event-time-1"`, distinct retained instant field
keys `startKey` and `endKey`, and a positive decimal-string `maxSpanUs`
of at most 32 days. Telemetry admits interval reports only if both bounds
are present, the start precedes the end, the end equals the journal event
time, and the span stays within the limit. Changing the contract changes
the source schema pin.

Current source/device/account state is checked on every description. Disabled
sources remain inspectable by their owner with `sourceActive=false` and a changed
state digest; inactive/decommissioned devices are unavailable. Foreign and missing
sources return404, revoked accounts403, invalid revisions400, and absent/stale or
corrupt schema journals503. Reads do not provision accounts, backfill journals or
reconstruct corrupt snapshots. Sources predating V027 obtain their first journal
entry through an ordinary authorized source configuration write.

The shared `ConfigurableOwnerReads` transport uses fixed read routes, signed bearer
forwarding, bounded bytes/time and disabled redirects. The common-vcl description
codec checks closed envelopes, identities, digests, exact integer revisions and
the implemented profile. Analytics `SourceBindingSnapshots` additionally checks
the expected actor/account and exact current revision, and refreshes original pins
at compiler scope fences and private inspection. Metadata never grants execution,
reference or evidence-policy rights. This profile always reports
`ruleExecutionReady=false`, unknown transition/deletion coverage, unqualified event
time, and null input generation/maximum gap. The separately opt-in accepted-input
journal preserves Boolean, TEXT and explicitly mapped DECIMAL observations and exact schema pins but does not
change these readiness claims; see [telemetry events](../events/telemetry-events.md#telemetryobservationsacceptedv1).
Reviewed automation grants now exist in [alert owner](alerts-api.md#reviewed-automation-grants).
Actual input generations/cutover are implemented; sealed mappings, qualified
upstream coverage and temporal RULE qualification remain open.

Source-owner verification:26 signed H2/PostgreSQL cases cover journal identity,
concurrency, rollback, stale/corrupt metadata, authorization, real transport/codec
round trips, sanitized schema/runtime publication, atomic outbox rollback and
exact configuration fingerprints above2^53. The final full backend checkpoint
passes1250 tests across16 modules. Analytics adds6 adversarial source-metadata HTTP
cases. These checks qualify the schema prerequisite and separately documented
accepted-input journal, not upstream completeness or temporal RULE outcomes.

### Source mapping preview

Send a signed `POST /data/source-bindings/{id}/mapping-preview` with
`schemaRevision`, `fieldId`, `conversion` and one typed JSON `sample`.
The selected field must exist in the current authorized schema. Use
`{"method":"identity"}` for BOOLEAN or TEXT, or
`{"method":"parse_boolean","trueTokens":["on"],"falseTokens":["off"]}`
for TEXT. Tokens are exact and case-sensitive. Overlapping lists and
unrecognized samples are errors.

The response contains a typed `literal`, the pinned schema snapshot ID
and a mapping digest. Preview has no write effects.

Confirm a TEXT-to-BOOLEAN preview with signed
`POST /data/source-bindings/{id}/mapping-confirmation` and the same
`schemaRevision`, `fieldId`, `conversion` and `mappingDigest`. Confirmation
rechecks source ownership, the current schema and the digest under the write
lock. It returns an immutable receipt with the source's device ID and the
applied schema revision, snapshot ID and definition digest. Repeating the same
confirmation returns the same receipt. Changing the conversion in the request
without a matching preview digest or applying a stale preview returns a conflict.
To replace a reviewed conversion, preview it against the current revision and
confirm the new digest. The field continues to accept raw TEXT while its
normalized schema exposes BOOLEAN.

Ingestion applies the confirmed tokens to the configured text value before
publishing the BOOLEAN descriptor and the new schema pin. Accepted events carry
their normalized values and original schema pin for journal replay.

Signed `POST /data/source-bindings/{id}/mapping-replay` accepts
`schemaRevision`, `fieldId` and one raw `sample`. The owner checks current
access and resolves the conversion recorded for the requested revision. The
response contains the typed literal and original schema snapshot ID and
definition digest. This is a read-only evaluation of one raw field.

Signed `POST /data/source-bindings/{id}/mapping-event-replay` evaluates a full
raw `metrics` object with the requested `schemaRevision` and exact
`schemaSnapshotId`. It runs the same normalization as live ingestion and
returns the normalized metrics, device ID and original schema pin. The
operation performs no ingestion or publication. A revision that predates
policy archiving returns `MAPPING_REPLAY_UNAVAILABLE`. An invalid digest
returns `MAPPING_RECEIPT_INTEGRITY`.

## Source capture description

Live HTTP ingestion with a valid source token and binding may include
`sourcePosition: {"epoch":"<UUID>","sequence":"1"}`. Start at sequence 1
for each producer epoch and binding schema snapshot. Ingestion carries the
position separately from metrics. Reusing one position for a different
accepted event conflicts.

The service-token-protected
`POST /internal/observations/source-progress?producerEpoch=<UUID>` reads
position evidence for an exact source schema pin. `contiguousThrough` and
`highestObserved` are decimal strings. `gapPresent` means a position is
missing in that observed span. A continuous sequence still returns
`upstreamCoverage: UNKNOWN`.

An operator-configured trusted attestor can submit a completeness statement
to `POST /internal/observations/source-coverage` with a separate
`X-Coverage-Attestor-Token`. Configure `APP_INTERNAL_COVERAGE_ATTESTOR_TOKEN`
independently of the ordinary read token. Submit the exact schema `pin`,
`proofId`, `inputGeneration`, `producerEpoch`, `throughSequence` and
past half-open event-time bounds `from` and `until` of at most 24 hours.
The optional `eventTimeBasis: "SOURCE_EVENT_TIME_V1"` additionally attests
that the source timestamps are authoritative for interval membership in
that statement. Supply it only when the attestor can establish that property.
Telemetry records the acceptance time from its database. A covered response
includes the optional basis in `evidence` and the database time in `acceptedAt`.
The internal worker lookup can also return an owner-composed proof for a
gap-free interval of up to 34 days. Its `evidence.parts` lists the original
qualified proof IDs in order. Every read rechecks the constituent proofs in
the same input generation and producer epoch.
The attestor must independently establish that the source captured every
required transition in those bounds.

The journal returns `COVERED` only for an attested interval with all positions
through the claimed sequence present and no contradictory observations.
Gaps return `UNKNOWN`; the trusted statement is retained under its immutable
proof ID. A read after the missing positions arrive re-evaluates the statement
without requiring the attestor to send it again. A different statement under
the same ID conflicts. Other insufficient or mismatched evidence is not retained.
The service-token-protected `POST /internal/observations/source-coverage/{proofId}/read`
rechecks a stored proof with the exact schema pin. A later observation that
contradicts the attested interval changes the result to `UNKNOWN`.

Analytics reads the proof through signed
`POST /data/source-bindings/{id}/coverage/{proofId}` with the exact schema
pin as the body. Device-service checks source access before and after its
telemetry request. A covered result includes `requiredCheckpoint`, the
highest journal checkpoint needed by the attested sequence prefix.
Analytics accepts it only when its captured head includes that checkpoint
and the attested interval encloses the requested bounds.

For an operator-qualified source with an authoritative closed event log, run
`python scripts/attest-source-coverage.py --manifest <statement.json> --state <private.sqlite>`.
The statement profile is `source-coverage-statement-1` and contains canonical
`proofId`, `bindingId`, `accountId`, `deviceId`, `schemaRevision`, `snapshotId`,
`producerEpoch`, `throughSequence`, `from`, `until`, `evidenceFile` and
`evidenceSha256`. The evidence file is bounded NDJSON with every producer
position from `1` through `throughSequence`, one `{"sequence":"1","eventTime":"...Z"}`
record per line. The operator is responsible for establishing that this closed
log is the source's complete transition log for the stated interval. A sampled
feed cannot use this qualification.

Supply `VCL_OWNER_BEARER_TOKEN` and `VCL_COVERAGE_ATTESTOR_TOKEN` through the
process environment; set the latter to the same distinct value as telemetry's
`APP_INTERNAL_COVERAGE_ATTESTOR_TOKEN`. Compose leaves this value empty by
default. Local Kubernetes supplies it only to telemetry-service through the
`voltcanary-coverage-attestor` Secret, also empty until configured. The CLI stores only the statement digest,
proof ID, generation and status in its SQLite state. Re-run it after exit code
`2` or a temporary service failure. It renews the signed source read, refuses
schema/generation drift and conflicting proof reuse, and reads the proof again
after a gap closes. Exit code `0` means the current owner read says COVERED;
the result can return to UNKNOWN if later evidence contradicts it. Do not put
either credential in the statement or SQLite file.

`python scripts/source-coverage-docker-smoke.py` exercises a synthetic source
whose closed producer log is written before ingestion. It verifies gap UNKNOWN,
restart UNKNOWN, recovered COVERED and late contradiction UNKNOWN through the
signed source owner. Production connector qualification remains open.
The first candidate is an existing REST source with a complete producer journal.
Its authoritative API must provide an immutable ordered prefix and an explicit
event-time closure covering every transition, deletion and correction for the
binding. A REST ingest ACK, contiguous accepted positions, or a quiet polling
interval cannot establish that guarantee. The operator must verify the producer
journal identity, prefix digest, closure identity, retention and behavior after
restart and late events before submitting a statement for this source. No
production REST source is marked qualified by the synthetic acceptance run.

`GET /data/source-bindings/{id}/capture?schemaRevision=1` requires the same signed
owner as source describe and an explicit exact current schema revision. It returns
`{source:<ordinary source description>,capture:<telemetry journal readiness>}`.
The source schema snapshot and input-generation UUID are distinct identities;
legacy source describe capabilities are not silently upgraded.

For the pending autonomous RULE worker, `GET /internal/vcl/source-bindings/{id}/capture`
accepts only `X-VCL-Worker-Assertion`, never a saved user bearer. The 30-second
machine assertion binds the reviewed actor, account, rule, deployment epoch,
grant, exact binding, `SOURCE_CAPTURE` operation and request digest. Device-service
still resolves that actor's current account/source authority before and after
the telemetry read. Analytics loads an ACTIVE deployment, checks the binding in
its sealed graph and rechecks deployment state after the owner response. Configure
the same base64-encoded 32-byte or longer `APP_VCL_WORKER_HMAC_KEY` in
analytics-service, alert-service and device-service. It is empty by default. This route alone
does not enable scheduled RULE execution or qualify grant/coverage/effect reads.
`python scripts/vcl-worker-source-docker-smoke.py` verifies the private read
against a real device owner and restores the original Compose configuration.
The companion private POST paths `/internal/vcl/source-bindings/{id}/observations`
and `/internal/vcl/source-bindings/{id}/coverage/{proof}` bind the exact JSON
request bytes to the assertion. They retain the same bounded journal/proof
response profiles and current owner checks. Analytics validates the captured
source pin and generation and rechecks the deployment after each response.
Neither path alone confirms full source coverage or activates RULE execution.
The private GET `/internal/vcl/datasets/{id}?schemaRevision={revision}` uses
the same exact-request machine assertion with `DATASET_DESCRIPTION` scope.
The device owner resolves the actor's active account before and after its
existing dataset description read. Analytics permits only a `lookup_asof`
target in the ACTIVE sealed graph, applies the compiler's prepared lookup
projection to the returned definition, compares its sealed dependency digest,
and renews deployment state after the response.
This metadata read does not authorize row queries or rule execution.
For a previously sealed native as-of snapshot, the private POST paths
`/internal/vcl/datasets/{id}/query-asof-manifest`, `/query-asof` and
`/query-asof-split` accept an exact-body `X-VCL-Worker-Assertion` with distinct
`DATASET_ASOF_MANIFEST`, `DATASET_ASOF` and `DATASET_ASOF_SPLIT` operations.
The request bodies match the user-facing as-of read bodies above. Device-service
checks the actor's current account and dataset scope before and after the
bounded snapshot read. Analytics permits only the target and projection of a
sealed `lookup_asof` node, renews grant and dataset authority, validates the
snapshot manifest and rechecks the ACTIVE deployment. These routes cannot seal
a new snapshot. Autonomous snapshot selection and execution remain pending.
Analytics can now scan a bounded set of ACTIVE deployment candidates and
recheck their sealed graph's source, dataset, grant and policy owners with
machine identity. This admission is read-only. The source capture still reports
`ruleExecutionReady=false`, so it does not consume due timers or publish rules.
A retained journal cut can also be reconstructed through signed machine
capture, observation-page and coverage-proof reads. The restored cut keeps its
original generation, checkpoint and digest fences. Due-timer processing still
uses the manual authorization path until the autonomous worker is qualified.
For direct journal windows, analytics can reconstruct the durable diagnostic
recipe under an ACTIVE sealed deployment using those machine reads. It checks
the original rule, source and keyed-state pins and rejects a lookup node whose
snapshot selection has not been prepared. This infrastructure does not itself
run a scheduled timer or create an alert.
An opt-in analytics scheduler (`app.vcl.worker.enabled=true`) can discover
ACTIVE deployments and drain bounded due diagnostic windows with machine
owner checks and exact account/rule/epoch timer scope. Owner failures leave
timers pending. It writes only unfinalized MEASURE outputs and is disabled by
default while coverage and worker recovery acceptance remain open.
The alert owner also accepts a private machine GET
`/internal/vcl/grants/{id}?grantEpoch={epoch}`. Its exact assertion binds the
grant, rule, actor, account and deployment epoch. Alert-service reloads the
effective grant from SQL, renews its original source and dataset pins through
device-service machine reads, then reloads the grant and account before
returning. Analytics compares the result with the sealed execution-grant pin
and checks deployment state after the response. Worker source/page/proof/dataset
reads renew this grant before disclosure and after the owner response. A
revoked, expired, changed-scope or unavailable grant fails closed. This read
does not authorize an alert write; effect-time permission remains separate.
The alert owner accepts a separate signed private GET
`/internal/vcl/evidence-policies/{id}?revision=1`. It verifies the same
actor/account/rule/grant/deployment scope, renews the grant and owner pins,
then rereads the policy and account. Analytics requires the policy in the
sealed `emit_alert` graph, matches its resource pin digest, and checks grant
and deployment state after the response. This is a readiness read; durable
alert creation must still recheck permission at write time.

The source owner resolves account/device/schema pins from current SQL and sends
only that server-owned pin to the fixed internal telemetry readiness RPC with
the configured service token. It then renews source/account authority and exact
source state before returning. A concurrent disable/revision change fails409;
revoked account403; foreign/missing source404 for an existing active account.
Caller account/role headers do not widen scope. Inactive sources are inspectable
with capture DISABLED and do not call the journal owner. Reads never repair or
mint schema/input generations.

Capture metadata contains the `accepted-boolean-text-2` profile, exact schema pin,
state, actual persisted nullable inputGeneration, generationStartCheckpoint,
headCheckpoint, generationStartedAt and journalReady. Checkpoints are exact decimal
strings. Readiness distinguishes disabled selection, missing schema, awaiting
input, pending work and a ready local journal. Metadata never attests upstream
transition/deletion completeness, event-clock quality, negative evidence, runtime
readiness or a sealed historical data snapshot. See [the event contract](../events/telemetry-events.md#observation-readiness-owner-read).

Internal owner failures, redirects, malformed or mismatched pins and fabricated
coverage/runtime flags fail503 with safe typed diagnostics. No upstream body is
returned. The ordinary schema endpoint still needs no telemetry RPC. The optional
capture route uses `TELEMETRY_SERVICE_URI`, `INTERNAL_SERVICE_TOKEN` and
`SOURCE_CAPTURE_OWNER_TIMEOUT` (default2s), all deployment-owned settings.

Analytics `SourceCaptureSnapshots` reads the same signed public owner contract.
It pins real generation and explicit cutover, reauthorizes before metadata use
and rejects source/scope/pin/generation drift or checkpoint rollback. New input
may advance the head while the snapshot retains its original captured head;
that head is not a sealed row snapshot. Grant/reference rights, full temporal
runtime and negative evidence are never inferred from journalReady.

## Circle geometry revisions

`PUT /data/spatial-features/{id}/circle` accepts
`{expectedRevision,geometry:{longitude,latitude,radiusMetres}}`.
Use expected revision zero to create the feature. Subsequent edits require
the current revision. Coordinates are WGS84 and radius is positive decimal
text in metres up to 20,100,000. Exact retries return the immutable revision.
`GET /data/spatial-features/{id}/circle?revision=N` returns a specific revision
and its digest. Both routes require current account ownership.

`GET /analytics/configurable-rules/circles/{id}/dependencies?revision=N`
verifies access to that revision with the device owner, then returns the
analytics owner's bounded dependency snapshot. The workbench shows compiled
rule bundles and their staged or active deployments before reviewing new
geometry. Deployments retain their immutable circle pins. Switching a rule
to the new revision uses the ordinary compilation and deployment review.

Vehicle fleet selection, GPS maps and per-vehicle monitoring zones use the existing
`/vehicles` console and telemetry monitoring owner. The shared manual/AI review extension
is described in the [monitoring contract](../flows/vehicle-monitoring-verification.md#shared-vehicles-and-ai-workflow--2026-11-18).
The standalone circle dashboard widget and its assistant composition path were
withdrawn on 2026-11-18; generic immutable geometry and rule dependency APIs above
remain supported.

## Accepted observation prefix reads

`POST /data/source-bindings/{id}/observations` accepts exactly
`{pin:<capture.pin>,inputGeneration:UUID,afterCheckpoint:string,throughCheckpoint:string,limit:integer}`.
The pin/generation come from the owned capture description. Limit is1..128;
checkpoints are canonical nonnegative decimal strings through INT64 max. The path
binding must match the pin. Caller pin/account fields do not grant access: current
signed source/account authority and the owner-issued schema publication are checked
before the fixed private telemetry RPC, then renewed after it. The response's
exact field IDs/types must match that separately authorized source definition.

The accepted-observation-page-1 response contains `profile,request,nextCheckpoint,
hasMore,truncationReason,observations` and conservative quality flags. Each
observation retains its original accepted payload, event ID, generation,
checkpoint, schema pin, timestamps and typed KNOWN/UNKNOWN values. An explicit
stored telemetry mapping of type `decimal` accepts decimal text with up to 38
digits of precision and 12 fractional digits. Its journal value is canonical
decimal text with a registered unit. Legacy numeric mappings keep their existing
semantics. Spatial mappings use `point` with a WGS84 object
`{longitude,latitude}` or `spatial_ref` with a canonical feature UUID.
Both use the unit marker `1`. The journal exposes them as POINT and
REF(SPATIAL_FEATURE), and the reference does not grant access to geometry. FALSE
and empty text stay known. Checkpoint ordering preserves separate same-timestamp
events. `nextCheckpoint` advances only over a contiguous returned prefix;
PAGE_ROWS or PAGE_BYTES explains remaining data. The service returns at most
240000 observation bytes and262144 total response bytes. Newer accepted inputs
do not change the requested throughCheckpoint. No cursor silently selects latest.

The optional pair `eventFrom` and `eventUntil` selects a half-open event-time
range of up to 34 days at the same pinned checkpoint. In this mode returned
checkpoints increase in journal order and can skip observations outside the
range. `hasMore` describes matching rows, so it can be false even when
`nextCheckpoint` precedes `throughCheckpoint`. The range read uses the same
schema and row integrity checks as a contiguous prefix.

The cut must be within the actual generation start/head. Legacy facts before
cutover cannot be retagged. Mixed schema revisions require an explicitly matching
checkpoint range; old revisions are not silently filtered out. Missing facts or
corrupt payloads fail, never return a complete empty page. HTTP400 means invalid
request;409 means stale pin/generation/schema/cut;422 means source/capture
unavailable;503 means owner/storage/integrity failure. Existing account
revocation403 and foreign/missing source404 remain unchanged.

V020 stores a canonical payload digest with each new accepted fact in the same
transaction. This read profile verifies that digest before returning values.
It also checks that the accepted input receipt has a valid digest format and
that capture cleared its pending payload. An inconsistent receipt makes the
prefix unavailable.
Existing facts keep a null digest; read/replay never backfills it or invents
historical integrity evidence. Such a prefix fails422 until the caller selects
an explicitly supported retained range. Existing payloads, generations, replay
receipts and event-wire contracts are unchanged.

This is a retained captured prefix, not a retention lease or sealed snapshot.
hasMore=false means only that the requested local cut was reached.
upstreamCoverage=UNKNOWN, eventTimeProvenance=UNQUALIFIED,
negativeEvidenceReady=false and ruleExecutionReady=false remain mandatory.
The private `POST /internal/observations/read` uses the same request/response
codec, mandatory internal token and repeatable-read SQL with a2s query budget.
It does not mint generations, repair metadata, materialize derived data or admit
RULE/alert effects.

Analytics SourceCaptureSnapshot.readPage(afterCheckpoint,limit) invokes this
signed route using its original generation/head and fresh owner fences before
and after the page RPC. Wrong fields/types, expanded flags, drift, redirects,
expiry or owner failures reject the page. No result becomes runtime authority.
