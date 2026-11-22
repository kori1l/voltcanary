---
name: voltcanary-backend
description: VoltCanary Java backend workflow for Spring Boot services, Maven modules, REST controllers, internal service boundaries, Kafka consumers/producers, Flyway migrations, shared common modules, and backend tests. Use when Codex modifies or reviews files under backend/ or backend-facing API/event contracts.
---

# VoltCanary Backend

## Before Editing

Use `$voltcanary-openspec` first for non-trivial API, event, database,
security, deployment, ML/analytics, or cross-service behavior changes.

Read the local service shape before editing:

- Parent build: `backend/pom.xml`
- Shared modules: `backend/common/common-events`, `common-web`,
  `common-security`, `common-test`, `common-vcl`, `common-business-contracts`
- Services: `api-gateway`, `identity-service`, `device-service`,
  `ingestion-service`, `simulator-service`, `ai-agent-service`,
  `telemetry-service`, `analytics-service`, `alert-service`, `dashboard-service`
- Contracts: `docs/current/api/*.md`, `docs/current/events/*.md`,
  `docs/current/architecture/02-service-boundaries.md`

## Spring Boot 4 and Spring AI

For AI-first work, follow the
[priority index](../../../openspec/changes/README.md#ai-first-execution-priority).
LangGraph4j core1.9.9 is now integrated through AgentGraphRunner and the single
adapter/langgraph4j implementation; preserve Spring AI exchange, budget and
domain ports rather than adding stock agent/tool executors. New plans pin the
engine/definition in schema2; native checkpoints commit inside the authoritative
plan JSON using existing revision/lease transactions. Legacy schema1 reads and
saved-review execution remain supported. Generation requires an explicit new
plan. The serial emitter stays on the caller thread for trace context.
Registered name consultations use compiled in-turn subgraphs and the same
plan fences, ledger and trace. Keep dispatch authority in the application
and apply G7's own acceptance to independent worker wakeup.
See [implemented boundaries](../../../docs/current/architecture/spring-ai-assistant.md#langgraph4j-core-integration--2026-11-11).
Qualify a different released artifact before changing the pinned version.

The shared platform uses Spring Boot 4 / Spring Cloud 2025.1 and the assistant
uses Spring AI 2. Use modular webmvc/restclient/Flyway/test starters and relocated
Boot 4 test annotations. Let the Boot BOM manage framework, Kafka and Micrometer.
Application Jackson 2 contracts are retained through `spring-boot-jackson2` and
`JacksonRestClients.builder()` for programmatic domain HTTP clients. Spring AI
provider models live in `ai-agent-service/adapter/springai`; never reintroduce
handwritten LLM HTTP transports or implicit retries/tool loops outside the durable
reservation and reviewed execution boundaries. Persisted conversation projections
are the memory source; avoid a second in-memory ChatMemory store. See
[the current runtime](../../../docs/current/architecture/spring-ai-assistant.md).

For non-trivial AI extensions also read the target
[Spring implementation contract](../../../docs/target/ai-assistant-spring-implementation.md)
and its reuse/advisor/AOP matrix before choosing framework hooks. Keep budget,
confirmation, idempotency and semantics explicit; custom CallAdvisors guard the
prepared manifest/existing dispatch reservation, and the narrow observation aspect
adds telemetry only. Wire Boot observation registries into manually built models.
Do not introduce hidden ToolCallingAdvisor/validateSchema retry loops, a second
ChatMemory, or @Async-only durability. Test real proxy/chain behavior (SPR01-SPR15),
not annotation presence. These are target extensions, not existing implementation.

## Architecture Rules

Preserve the hexagonal boundary described in `openspec/project.md` and
`openspec/specs/backend-architecture/spec.md`:

- Keep business decisions in domain/application services.
- Keep controllers, Kafka consumers/producers, repositories, HTTP clients, and
  SDK clients as adapters.
- Do not make domain code depend on controllers, repositories, Kafka, HTTP, or
  external SDKs.
- Keep analytics resilient when ML service is unavailable.
- Preserve maintenance exclusion semantics: `excludeFromAnalytics` affects
  analytics input; `excludeFromMl` is sent to ML as `excludedIntervals`.
- Keep runtime configuration in `config` packages as typed
  `@ConfigurationProperties` or bean wiring. Do not add new generic
  `*ConfigService` or `*SettingsService` classes in `service` packages for
  user-managed policy, preferences, simulator YAML, or model-selection behavior;
  use business names such as policy, preferences, profile, selection, or
  lifecycle use cases.
- Keep application services focused on one use case or closely related
  workflow. When a production `@Service` is already large, has many constructor
  collaborators, or owns validation, defaults, serialization, persistence,
  external I/O, and DTO mapping together, extract focused collaborators before
  adding more behavior.
- Avoid wildcard imports and compressed multi-statement one-line methods in
  production Java code; they make boundary drift harder to review.

## Implementation Notes

VCL uses common-vcl for strict grammar, types, registry and checked scalar/graph
IR, with pure scalar execution in analytics-service's `vcl` package. See
[the current boundary](../../../docs/current/architecture/vcl-compiler-front-end.md).
Keep owner authorization/snapshots outside the pure kernels and preserve numeric
AST v1. Grammar membership does not establish spatial/runtime readiness.
Pure VclWindowPlan projects compiled window_aggregate declarations into exact
bounded elapsed/calendar assignments and pins timezone rules for replay. Keep
aggregation, completeness/watermarks and durable state/effects outside that
assignment kernel; its existence does not qualify a temporal runtime.
VclIntervalUnionKernel separately computes exact union/exclusion arithmetic on a
bounded supplied batch. Preserve UNKNOWN/open/null eligibility and scope/version
fences. Its known duration (including empty-batch zero) is not a completeness or
negative-evidence acknowledgement. Do not infer grouping, deduplication, reference
cutoffs, state/correction persistence or effect authority from pure arithmetic.
VclWindowAggregateKernel projects shared compiled graph IR into pure bounded
COUNT/SUM/MIN/MAX/AVG reduction plans. Preserve the canonical rule decimal policy,
types/units, exact window membership and empty/null/unknown distinctions. These
batch results do not authorize ON_CLOSE/LOWER_BOUND_PROVEN emissions or establish
coverage/finalization, durable grouping/corrections or effect admission.
VclDurationWitnessKernel shares pure observed_within/sustained batch arithmetic:
zero duration requires an actual eligible TRUE witness, positive duration needs
actual endpoints and an explicit pinned bounded hold. Preserve full partition,
scope/plan and unknown/gap/error fences; no extrapolation or absence proof. Owner
coverage/session-occurrence routing and durable evaluator/effect gates stay separate.
VclTimingPlan supplies pure root timing arithmetic only: exact skew quarantine,
minimum qualified supplied progress minus root lateness once, UNKNOWN required
progress and independent idle expiration. It qualifies no actual progress owner
or input generation; idle time never proves an exit/absence. Persisted monotone
watermarks, coverage/reference completeness and effect gates remain separate.
KeyedStateStore/JdbcKeyedStateStore (V035) provide isolated bounded state/inbox
infrastructure, not an activated evaluator. Preserve full key tuple/hash checks,
immutable registration/execution pins, database-clock lease fences, bounded SQL/
lock waits and atomic CAS/state/input receipts. Exact replay after lost ACK or
lease expiry is metadata only. Timers/derived/rekey/outbox and actual owner/grant/
generation/coverage admission must share the eventual fenced runtime boundary;
do not expose this infrastructure as execution authority.
V036 adds atomic complete timer schedules, restored with key acquisition, plus
bounded account-scoped due discovery. TIMER/OBSERVATION inbox namespaces are
separate. Consume only the current scheduled state revision and exact saved timer
digest after fresh database due/lease/CAS checks. Timer discovery is not a lease,
owner authority, observed exit or finalization; no active scheduler exists yet.
V037 adds immutable bounded output/owning-receipt ACK metadata to the same keyed
state/timer/inbox commit. Preserve full rekey account/rule/epoch fences, exact
output reuse/conflicts, retained dedup budgets and post-SQL fresh lease checks.
V038 adds atomic local REKEY consumption into a supplied target transition and
source ACK, with full identity/pin fences, ordered dual-key locks and verified
immutable local receipts. Both leases must survive the final SQL; retained
target inbox budgets include rekeys. No autonomous evaluator/derived/rekey worker
or external sender exists; supplied external receipt hashes
cannot replace actual owner receipt verification, grant/generation/coverage
admission or alert/notification authority.
V039 adds integrity-checked input/output-set receipts without backfilling legacy
rows. DurableDurationWitness integrates the shared kernel with a128-sample/64KiB
single-witness keyed profile and immutable result intents. Preserve original-result
replay after corrections/lost ACK, lookup/acquire races, rule/partition/window pins
and explicit failure for unbound legacy receipts. No autonomous graph worker,
actual owner/generation/coverage admission or derived/alert effect is qualified.
The scalar registry fingerprint includes VclScalarContracts. Spatial evaluation
uses versioned WGS84/circle snapshots plus fresh owner authorization. Bundle
preparation/storage live in the same focused module, with JDBC in `vcl/adapter`;
test attestations do not replace real configurable owner adapters. The stored
OWNER_VALIDATED_IR envelope is not a rule activation grant.
device-service's `configurable` module owns native collection
schema/review/provision/describe, reviewed single-row CAS/history capture, bounded
LIVE seek queries and the real SQL-backed metadata adapter.
See [its implemented API](../../../docs/current/api/configurable-native-data.md).
AI native-table authoring is default-off in `nativeops`, using the existing
primary pipeline and PlanStore. Preserve reserved IDs, owner-bounded review TTL,
exact receipt/schema readback and GET-only expired UNKNOWN recovery. Controlled
transport/preparation tests do not qualify model quality or compound workflows.
The LIVE profile advertises explicit pushdown/order restrictions; historyReady
stays false until historical reads exist. Bounded atomic imports and reviewed
native generation replacement now use the same owner module. Route native SQL
through NativeSqlLayout(NativeDataset), using the refreshed physical pointer
and generation journal; schema-only layouts identify initial provisioning, not
the current table after replacement. Keep shadow DDL outside active-data
transactions, verify exact READY contents/history and preserve the shared
account/head fence for cutover, receipt and outbox. Retired generations and sealed
snapshots remain retained. Full import/migration/sharing/retention/performance
and UI/AI gates remain open. Provisioning must never recreate
a missing READY table as an empty dataset.

The same module journals immutable source binding schemas during ordinary source
writes, even with Kafka publication disabled. Signed `/data/source-bindings/{id}`
reads never backfill or repair metadata. `legacy-source-schema-1` exposes retained
Boolean/text fields and explicit numeric mapping gaps; it has no input generation,
coverage proof or RULE readiness. SourceBindingSnapshots and shared transport/codec
checks preserve that boundary. Metadata snapshots cannot replace actual capture,
grant, evidence-policy and bundle owner validation.

alert-service's `configurable` module owns RULE identity reservations and their
SQL key/quota/expiry fences. Fresh account authority is mandatory even with the
legacy authorization toggle disabled; the local account fence is not authority.
Reserved rule/output/receipt IDs do not create grants or activate rules. Use the
existing owner control protocol for subsequent reviewed grant/effect lifecycle.

Alert-service reviewed grants use `source-native-fields-grant-1`, exact signed
source/native field pins and the original RULE reservation lease. Keep durable
APPLYING before grant effects and atomic effect/receipt commits. Reconcile must
not dispatch; completed create receipts do not become current grant state.
Subtractive owner revocation survives account/source loss, with atomic epoch and
receipt updates. Use a fresh database clock after locks (not PostgreSQL transaction
CURRENT_TIMESTAMP). Permission metadata does not qualify capture/runtime or the
unfinished atomic alert/effect admission boundary; no delivery channels are granted.

Reviewed `private-reference-evidence-1` policy metadata uses the same local grant
fence, with exact epoch/scope and bounded retention/reference limits. Keep policy
creation/receipt atomic after durable APPLYING. Same-key reservation replay is
owned metadata, including after grant expiry/revoke; it never renews usability.
GET policy refreshes actual grant/source owners and checks local epoch again after
remote I/O. Compiler policy pins require the separately authenticated grant
snapshot. Metadata does not implement evidence storage, retention cleanup or
autonomous alert/effect admission.

Telemetry `observation/` owns the opt-in accepted Boolean/TEXT event journal.
Schema pins come from source-owned `source.binding-schema.v1` envelope publication,
not incoming metrics. Preserve exact event replay/conflict, same-timestamp facts,
pending exact-schema recovery and atomic journal/checkpoint/outbox commits. Raw
capture failures must hold delivery; device deletion uses the existing local fence.
Keep upstream coverage UNKNOWN and capture/RULE readiness false until their actual
contracts are qualified. This journal is not a temporal evaluator or complete
upstream transition feed; legacy numeric AST behavior remains separate.

V019/profile2 binds new accepted facts to a real persisted input generation and
explicit checkpoint/time cutover; legacy facts/receipts remain generation-less.
Assign generation only inside the actual first observation transaction, never
from schema identity or reads. Internal observation readiness is a mandatory-token
consistent SQL read. Signed source capture describe renews actual source/account
scope after its RPC; the base source describe stays independent of telemetry.
Compiler capture snapshots preserve cutover and a captured head, allow head
progress and reject rollback/retargeting/generation drift. Journal readiness does
not authorize temporal evaluation, negative evidence or sealed snapshot reads.

V020 stores canonical-payload checksums only for newly accepted observations;
replay/reads never backfill legacy facts. Bounded observation pages use exact
schema/generation/checkpoint cuts and fail on gaps, checksum absence/corruption or
schema drift instead of filtering rows. Signed source owner reads renew authority
before and after telemetry I/O. Analytics pins the original head, checks actual
schema fields and preserves UNKNOWN/UNQUALIFIED/false quality flags. Local prefix
continuity is not upstream coverage, a sealed snapshot or runtime admission.

Assistant observability uses the shared `AssistantTraceRecorder`/`AssistantTraces`
port and request-local `AssistantTrace` spans. Keep required starts/outcomes before
further side effects, preserve receipt-based recovery, and never retry a mutation
because a diagnostic write failed. Capture only bounded observable artifacts;
exclude credentials, headers, raw upstream bodies and private reasoning. New
verification/recovery stages reuse this recorder rather than creating another store.

Public endpoints usually live in `controller` packages; internal endpoints use
`/internal/*`. Java services expose `GET /actuator/health` and
`GET /internal/ping`.

For database changes, add Flyway migrations under the owning service's
`src/main/resources/db/migration` directory and update OpenSpec/docs when the
schema or retention semantics change.

Telemetry keeps portable migrations in `db/migration` and database-specific SQL
in `db/vendor/{vendor}` (PostgreSQL/Timescale and H2), both configured in Flyway.
Use the vendor location for PostgreSQL-only covering/partial indexes and retain
H2 compatibility; verify migrations in both environments.

For Kafka changes, update the owning common event type and `docs/events/*.md`
with topic names, versions, and compatibility expectations.

## Validation

Run the narrowest meaningful Maven command:

```powershell
cd backend
mvn -pl services/<service-name> -am test
```

Run `mvn test` from `backend/` for shared modules or broad backend changes.

For explicitly requested validation without Docker, use `mvn -Plocal-tests test`.
This profile excludes tests tagged `docker`; ordinary Maven tests retain container
coverage. Run `npm run architecture:check` from the repository root after changes
to package or Maven module dependencies. Shared business records belong in
`common-business-contracts`; keep `common-web` focused on transport concerns.
