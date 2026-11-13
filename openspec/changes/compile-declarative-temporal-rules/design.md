# Design: Bounded declarative derivations and temporal alert rules

The [shared configurable architecture](../../../docs/target/configurable-operations-platform.md) owns canonical semantics and service boundaries. Owner prerequisites proceed under the resumed program.

## Rule identity reservation increment

alert-service owns the existing control contract's RULE reservation endpoint and
reserved rule/output/receipt IDs. Reservation rechecks the signed actor's current
account through the device owner; a local account fence only serializes reservation
keys and quotas and never supplies authority. The owning SQL transaction fixes
IDs and a15-minute database-clock expiry. Same-key/same-body replay cannot extend
authority; changed payloads conflict. This increment creates no rule, grant, alert
or delivery intent. Grant preparation and RULE activation remain separate gates.

## Reviewed permission owner increment

V021 adds exact source/native field permissions for one reserved rule under
`source-native-fields-grant-1`. Review identity/lease come from the existing RULE
reservation. Prepare refreshes signed owner descriptions and binds their pins in
the digest. Apply refreshes them again; durable APPLYING precedes the transaction
that creates permission and commits its receipt. UNKNOWN recovery preserves the
same intent; reconciliation never dispatches. Expired permission/review plus
absence under the owner fence proves no effect. No capture or evaluator readiness
is inferred from permission metadata.

Revocation reserves a receipt, then atomically raises epoch and removes permission;
the signed original owner can remove permission after account/source revocation.
Create receipts remain immutable. Fresh database clocks after lock waits prevent
transaction-start timestamps from extending authority. The first profile grants
ALERT only, with no delivery channels. Actual alert/effect admission against the
current epoch and resource authority is still a separate unfinished boundary.

## Implementation Boundary

The reviewed `private-reference-evidence-1` policy is implemented as an owner prerequisite.
It stores explicit retention/reference budgets and exact existing grant epoch,
without exposing input values or creating alert evidence. Separate reserved
policy/review/receipt IDs and a15-minute database-clock lease prevent cycles in
RULE authoring; the lease is also bounded by grant expiry and never renews grant
authority. Prepare/apply refresh source/grant owners, and creation fences the
current local grant epoch atomically with the policy receipt. Policy immutability
and loss of usability on grant revoke are independent of actual evaluator/evidence
storage integration, which remains pending. Public-policy/sharing/delivery modes
are outside the first profile.

V022 persists its reservations, exact grant epoch/scope pin, normalized limits
and immutable policy/receipt. Same-key replay returns the original owned metadata
after grant loss without renewing authority. Creation reuses the account/grant
fence and fresh database clock; policy and successful receipt commit atomically
after durable APPLYING. Unknown-outcome reconciliation does not dispatch. Policy
GET refreshes actual grant/source owners, then checks local epoch again to avoid
returning stale usability after a revoke during remote checks. The analytics
adapter verifies a closed ordinary signed owner response and binds it to its
separately authenticated grant snapshot before every policy pin use.

**Normative complete design (2026-11-03):**
Use VCL/1 registry and compiler front-end G0; do not choose a competing CEL/SQL interpreter. Scalars have checked precision and three-valued truth; ERROR is separate. Stateful methods, interval union, typed as-of joins, corrections, durable keyed inbox/state/timer/outbox commit and activation readiness follow the language/storage contracts.

Contracts: [VCL](../../../docs/target/configurable-operations-language.md),
[storage/adapters](../../../docs/target/configurable-operations-storage.md),
[strict imports](../../../docs/target/configurable-operations-imports.md) and
[acceptance A01–A66](../../../docs/target/configurable-operations-acceptance.md).
These decisions supersede tentative storage/interpreter wording below.

alert-service owns rule definitions, analytics-service owns compiled stateful execution, telemetry retains numeric variable ownership. Use the normative VCL/1 pure typed kernels and compiler front end; no competing CEL/SQL/script interpreter. Compilation checks types/units/schema refs/operator versions, bounded fan-out/state and access. Runtime pins configuration but resolves temporal records under explicit as-of policy; event-time, missingness, durable timers, corrections and activation readiness are mandatory.

## Compatibility and Validation

### Keyed state/inbox infrastructure increment

V035 stores a versioned bounded canonical state snapshot, immutable registration
and execution pins, CAS revision, lease holder/fence/deadline and input-revision
receipts under an account-scoped key digest. The key incorporates logical rule,
deployment epoch, subject and occurrence/window identity. Isolated JDBC commits
retain and verify its full tuple alongside the physical digest. Transactions
serialize each key; a fresh database clock after lock acquisition fences expired
workers. State and inbox receipt commit together, and exact transition replay is
read-only even after an uncertain ACK or expired lease. Initial-state registration
cannot reset existing state. Per-key inbox exhaustion fails instead of evicting
deduplication evidence. This is infrastructure only, with no bean/route/worker;
actual authority/generation admission, global budgets, timers/derived/rekey/outbox
effects and their atomic evaluator transaction remain separate task5.6 gates.

### Atomic timer infrastructure increment

V036 adds bounded complete schedule snapshots to the same keyed transaction.
Acquisition restores the current state/schedule pair under the key lock. Discovery
uses fresh database time and returns candidates, not authority. TIMER and
OBSERVATION inbox namespaces are distinct. Timer consumption binds its ID,
scheduled state revision and exact saved digest, and checks current due/lease/CAS
before any state change. Schedule replacement and input receipt are atomic;
stale candidates cannot survive correction/removal, and a consumed ID cannot be
rearmed in the same transition. Actual scheduling/evaluator callbacks, qualified
owner/generation/coverage admission and derived/rekey/outbox remain pending.

### Atomic output metadata increment

V037 appends immutable bounded typed output intents inside the same key-locked
state/timer/inbox transaction. Exact output identity reuse preserves the original
record; changed content conflicts. REKEY targets preserve account/rule/epoch and
full subject/occurrence identity. Private reads verify retained canonical bytes,
digests and revisions. Fenced ACK metadata binds exact output plus owning receipt
identity/digest, remains immutable on replay and does not free retention budget.
Post-SQL fresh lease checks prevent an expired worker committing a long transition
or ACK batch. Rollback covers all local records. No sender, rekey/derived consumer
or actual alert effect is introduced; real owner receipt validation and current
grant/generation/coverage/evaluator admission remain pending task5.6 work.

### Atomic local rekey increment

V038 and KeyedStateStore.consumeRekey consume an actual retained REKEY output
into one supplied target transition. The operation verifies full account/rule/
deployment identity, identical execution pins, exact source-qualified input
digest/original output revision and the stored target. It locks both keys in
digest order, checks both database-clock lease fences, applies the target CAS/
state/complete timer schedule/output intents, stores an immutable local receipt
and acknowledges the source in the same transaction. Both leases are rechecked
after the final SQL. Self-rekey uses one lock/fence; opposite directions share
one lock order. Target inbox budgets include rekey receipts without conflating
source-qualified rekeys with ordinary observation/timer inputs.

Exact replay verifies the retained output, target transition, execution pins and
the matching source ACK against the actual local receipt. It returns the original
result without effects even after lease expiry. An unrelated external ACK cannot
pretend to be that receipt. This is a local storage/transfer primitive, not an
autonomous worker, typed evaluator, source/coverage admission or alert effect.
Task5.6 remains open for those integrations and derived output ownership.

### Pure duration-witness increment

VclDurationWitnessKernel projects the compiled observed_within/sustained
declaration into one immutable arithmetic plan. Positive duration requires an
explicit versioned hold contract no looser than both operator and root maxGap.
Zero duration needs an actual eligible TRUE observation; an empty batch, unknown
or known false cannot satisfy it. Equal-time contradictory facts do not erase an
actual zero-duration TRUE witness, but break continuous duration.

Positive duration measures only adjacent actual observation endpoints connected
under the approved hold limit. There is no extrapolation after the last sample
or inferred duration from sample count/one instantaneous event. A known false or
unknown resets continuity after its timestamp; an excessive gap cannot bridge
episodes. An earlier independently proven run is retained despite another gap.
The end-exclusive boundary can close a preceding hold but cannot supply a new
eligible instantaneous witness. No witness found returns UNKNOWN, never absence.

Inputs pin the full account/rule/deployment/subject/occurrence key, exact
microsecond window, owner scope, kernel and plan. The bounded4096-row/128-evidence
profile preserves failure and budget errors even if another sample is positive.
The result is supplied-batch arithmetic, not source completeness, finalized
coverage, session ownership, durable corrections or effect admission. It does
not close5.6 or103.1's stable occurrence/input and full operator requirements.

### Durable duration-witness increment

DurableDurationWitness uses the existing keyed state transaction and the shared
duration kernel for a bounded single-witness profile. It retains at most128
latest observation revisions within the64KiB state budget. Higher observation
revisions recompute the supplied batch; prior input receipts and result intents
remain immutable. It binds the canonical rule ID, full partition, exact window,
plan, hold and execution pins. Reuse with another window/plan cannot retarget an
existing registration. Invalid scope/rule/input fails before registration.

Each accepted revision commits typed next state, an input receipt and one
MEASURE result intent atomically. This profile has an empty timer schedule and
is not a multi-operator graph state or a scheduler. V039 adds input/output-set
digests and a receipt integrity digest to new inbox rows. Original-result replay
checks the actual retained input receipt and corresponding immutable output,
including after state advances/corrections or a lost SQL commit ACK. A second
receipt lookup after acquiring the lease resolves a concurrent first commit.
Changed input bytes at the same revision conflict. Source authority/coverage is
not supplied by a matching scope digest; real owner/grant/generation admission
remains a mandatory future integration boundary before autonomous use.

Existing inbox rows remain byte-for-byte legacy, with nullable new digests.
Generic exact-transition replay remains available; the new witness workflow
cannot fabricate an input/result binding for a legacy receipt and fails explicitly.
Append-only MEASURE intents are local metadata, not materialized derived data,
alert effects or notification authority. G4/5.6 and G0/1.4 remain open.

### Pure root timing increment

Timing pins require one positive revision and a well-formed snapshot digest
per input kind and ID. Conflicting revisions cannot act as separate inputs.

`vcl-timing-1` pins canonical rule timing and the exact source/reference dependency
set. Future admission uses event versus trusted accepted microseconds; overskew
progress cannot move the computed frontier. The minimum qualified supplied progress
subtracts root lateness once, preserving unknown required inputs and rejecting
missing/changed pins. Idle expiration is separate arithmetic and can request only
an UNKNOWN timeout, never an exit/absence conclusion. Full-range intermediate
arithmetic cannot wrap at year boundaries or maximum declared durations. Actual
qualified progress providers, generations, durable monotone watermarks and coverage
remain separate prerequisites; this projection grants no finalization authority.

### Pure window-reduction increment

The prepared journal window has a durable execution path. It pins the compiled
window and timing plan, source cut, subject, execution epoch and optional
coverage proof. The first transition stores the bounded reduction and a
window-close timer. The due timer consumes the saved reduction and queues
one immutable measure with the current proof outcome. Rule grants are
rechecked around the owner reads and storage operations. The measure is
diagnostic and carries `finalized=false`.

The second execution profile binds the full compiled direct
`read_stream → window_aggregate → compare → emit_alert` graph. It uses the
shared scalar compiler and evaluator for the predicate, persists the decision
with the aggregate and records the terminal node ID without dispatching an
effect. Separate profile keys preserve earlier window state.

The recovery profile stores the canonical rule and original captured cut with
the staged state. On restart the application supplies current owner authority
and reconstructs the window from the SQL recipe. Recovery compares the captured
prefix, schema, generation and execution pins before consuming the timer.

`vcl-window-aggregate-1` derives an immutable reduction plan from shared checked
graph IR, verifying registry/units, predecessor value type and output type. Its
decimal policy must match the canonical rule declaration. Kernel/plan/owner-scope
and actual window membership are fenced. COUNT/SUM/MIN/MAX/AVG process a bounded
unique-ID supplied batch with exact membership, type/unit/range checks and explicit
UNKNOWN/empty behavior. Exact accumulation and final-only decimal policy avoid
order-dependent intermediate rounding or overflow. Calendar and rolling assignment
reuse VclWindowPlan. This increment supplies no coverage/finalization, grouping,
durable corrections or effect authority, so broad runtime tasks remain open.

### Pure window-assignment increment

analytics-service owns `VclWindowPlan`, an immutable pure projection of a compiled
`window_aggregate` node's window declaration. `vcl-window-assignment-1` assigns
microsecond instants to half-open FIXED, ROLLING or local DAY/WEEK/MONTH intervals.
Elapsed windows retain the31-day limit; rolling fan-out is checked before use
and capped at64. Calendar months use actual local boundaries and allow up to32
elapsed days. ISO week starts, midnight gaps, repeated hours and skipped local
dates follow the pinned Java timezone rules. The plan digest binds the exact
declaration, kernel version and server timezone-rules version. Replay requires
both digest and timezone pins; a changed/unavailable ruleset cannot silently
reinterpret stored windows. Out-of-range endpoints and submicrosecond instants
fail instead of being clipped or rounded.

This kernel assigns intervals only. It does not count/sum observations, establish
coverage or reference completeness, finalize watermarks, persist state/inbox/
timers, admit alert effects or activate rules. Broad tasks1.3/1.4/3.1 remain open.

### Pure interval-union increment

`VclIntervalUnionKernel` reduces an explicitly scoped/partitioned batch of typed
intervals and evaluated include/exclude predicates. It clips finite intervals to
the supplied half-open window, unions work and exclusions independently, then
subtracts only their intersections. Arithmetic uses exact elapsed microseconds;
empty/nested/adjacent intervals and breaks spanning multiple work segments cannot
double-count or make the result negative. Recomputing a corrected supplied batch
does not mutate earlier inputs and is not append-only correction persistence.

Unknown/null predicates or selected open/unknown/null intervals yield UNKNOWN;
both FALSE predicates leave a row unselected. Infrastructure failures and invalid
known literals cannot be hidden by FALSE/UNKNOWN. Diagnostics carry bounded
sorted observation/evidence IDs without raw values. The versioned kernel accepts
at most4096 unique observations and128 unknown evidence references in one32-day
window; these are execution bounds, not measured throughput/capacity claims.

The context pins kernel version, owner scope digest and explicit window, and the
caller supplies fresh authorization. No owner read, grouping, source completeness,
reference/break readiness, watermark, lower-bound proof, durable state or effect
permission is minted here. Even known zero for an empty supplied batch is only
batch arithmetic. This increment does not implement the whole registered
interval_union_window operator or close broad runtime/G0 integration tasks.

The typed interval-union plan now projects the checked upstream event
fields, compiles include/exclude predicates through the common scalar path,
and uses the existing local day/week/month assignment and timezone rules
version. Its supplied batch selects a typed subject before reducing clipped
intervals. Device and Telemetry preserve explicitly configured INSTANT
fields for bounds. The direct journal preview reads a bounded captured
prefix because a row may overlap the window even if its event timestamp
is outside it. Prepared lookup previews enrich journal rows from sealed
reference manifests. Point lookups use the event instant. Interval splits
must use the union operator's bound fields and project each owner-returned
segment with a deterministic identity. Unknown keys or reference outcomes
stay unknown. Finalization and alert effects remain separate.

Interval finalization requires a source contract binding the operator's
original start and end fields, the journal event time to the reported end,
and a maximum span. Telemetry enforces those constraints at capture. The
plan computes the latest possible report time as the window end plus that
maximum span. The direct-source finalizer requires owner coverage through
this horizon plus root lateness, checks the watermark, and reduces only the
relevant report range at the qualified cut. Its result binds the proof,
input digest and checkpoint. The journal worker stages each overlapping
calendar window once per subject, and the due worker commits its final
measure together with timer consumption. Pause records an unknown measure.
The direct graph evaluates its typed compare predicate against the final
duration and the subject captured in the recipe. A confirmed match appends
an alert proposal in the same transaction. Alert Service checks the source
contract and coverage through its reporting horizon. Older staged recipes
without a typed subject produce measures only. Readiness for the direct
on-close graph checks the source contract, typed comparison and effect policy.
The longest calendar window for the pinned timezone, reporting delay and root
lateness must fit the owner's proof interval. Reference-backed finalization
can restore the sealed start manifests under machine authority and repeat
the lookups at the covered journal cut. It rechecks the reference manifests
after evaluation, independently of event-time proof. The opening worker
projects each report through the same sealed lookup chain before assigning
subjects and windows. Readiness accepts the bounded lookup chain for
on-close execution and binds its prepared scope to the evaluator plan.
Filters in this chain run in graph order against the checked fields.
False drops the row. Unknown eligibility survives lookup projection and
can prevent a known duration when the row could affect the calculation.
Direct journal window aggregates use the same filter projection when
assigning windows and reducing the covered cut. An unresolved filter on
a potentially matching row keeps the aggregate unknown.
For COUNT lower-bound evaluation, the journal batch reads a strict
prefix of the assigned window at the owner proof cut, checks coverage
through prefix end plus root lateness, and runs the same COUNT and typed
comparison used for finalization. The prefix result retains the proof,
cut and input digest. The lower-bound COUNT profile reuses bounded window
timers and correction revisions. Alert Service verifies coverage through
the measured prefix and deduplicates against the full window occurrence.
Windows too short for distinct probe timestamps use the final close.
New on-close aggregate windows use the same correction schedule, with no
prefix probes. The worker retains the aggregate's existing subject dedup
identity and commits the deployment watermark together with the result.
Already registered legacy windows continue through their original schedule.
The aggregate preview also supports sealed event-time lookups before the
reducer. It reads a bounded journal range, pins the returned lookup data
in the input digest and preserves unresolved projected fields. The opening
worker projects each source row with the reviewed references before
choosing subjects. The due worker repeats the same lookup chain at the
covered source cut and rechecks its manifests. The start acknowledgement
binds the prepared aggregate scope. Interval-split lookup is reserved for
interval reduction.
Scheduled aggregates carry the typed subject literal through assignment,
covered reduction and comparison. REF, INT64 and ENUM identities use the
typed digest. Existing text-window profiles retain their dedup format.
For lower-bound evaluation the same plan clips work and breaks to a strict
prefix of the assigned calendar window. The source proof must extend through
the prefix end plus the maximum report span and root lateness. Any prepared
reference manifests are rechecked independently. The result carries the
exact prefix endpoint and input digest. The opening worker can schedule
bounded prefix checks for a lower-bound recipe. Each covered prefix is
compared with the rule threshold, recorded separately and followed by
another check or the final close. If a prefix proof is missing, the worker
can still finish once it obtains full-window coverage. A proven threshold
can emit an early alert. Alert Service verifies the reporting horizon of
the covered prefix, applies suppression to that interval and deduplicates
against the original calendar window. Readiness accepts the compiler's
monotone lower-bound mode. The close timer retains the early measure ID.
If full-window proof produces a known false decision, Analytics appends
a retraction linked to that early measure. Alert Service resolves the
existing alert and records the retraction. An overlapping report beyond the
final measure's checkpoint schedules a new evaluation for the same window.
The scheduled revision pins the minimum input checkpoint. Final measures
link to their predecessors and retain the last positive effect basis across
unknown outcomes. A known reversal can retract or reopen the same alert.
The rescheduling trigger is a source report, so a reference-only change
requires a separate reviewed reference revision and replay path.

Preserve canonical resource IDs, signed access, existing arithmetic AST v1, current circle endpoints and saved reviewed plans. New schemas/operators must be versioned and advertised only when implemented. Preview and runtime use the same canonical contracts. Require independent cross-domain, negative, replay and access tests; a model explanation is not evidence of completion. Keep implementation tasks unchecked until code and verification exist.


## Implementer clarification revision 2

The [owner control protocol](../../../docs/target/configurable-operations-control.md), corrected VCL grammar and A51-A66 clarify preparation, identity, typed imports, temporal lifecycle and uncertain recovery. They apply only to configurable-platform extensions; existing implemented APIs are not silently reinterpreted. Superseded scenario changes remain non-eligible for specialized implementation.

## Bus expressibility/readiness audit

The [bus readiness contract](../../../docs/target/configurable-operations-bus-readiness.md) adds BUS01-BUS10 capture, occurrence routing, exact door predicate and exit/non-arrival gates to this pending work. It preserves generic core ownership, required-stops-only user policy and all existing frozen fixtures/quality gates; it is not evidence of implementation.

## Autonomous window worker admission (pending 5.17)

The existing due-timer scan accepts a live user bearer and actor. A scheduled
worker must instead discover immutable active RULE deployments from SQL, each
bound to an account, rule, deployment epoch, compiled bundle digest, reviewed
grant and input pins. An activation or revocation changes the deployment state
under a database transaction; a queued timer alone never activates a rule.

Worker calls require a separate scoped service identity. The ordinary shared
internal token and a stored or refreshed user bearer are insufficient owner
authority. Source, grant, generation and coverage owners must derive the exact
account/rule/deployment scope from the reviewed deployment, authorize current
state on every execution, and renew it after remote reads. Loss of any owner or
proof leaves the due work pending/UNKNOWN. A timer lease and CAS serialize
competing workers; they do not replace current owner authorization. The first
worker increment may persist only diagnostic MEASURE outputs; alert effects
remain gated by task 4.3 and finalization/effect tasks.

V040 supplies the isolated durable deployment registry. A staged row binds
account, logical rule, deployment epoch, sealed compilation bundle digest and
reviewed grant ID; reads restore the bundle and verify rule/grant identity.
Activation executes a fresh authority callback under the row lock, then sets
the only active slot for that account/rule. Revocation clears the slot and is
irreversible for the epoch. This makes restart and competing activation state
durable, but the callback is not yet wired to a service principal or owner
protocol. No scheduled worker or user-facing activation route is enabled.

V041 adds the reviewed actor to that immutable deployment identity. V042
revokes pre-actor active rows and prevents their reactivation. Such rows cannot
be used to sign worker requests. The first machine
read uses a distinct 30-second HS256 assertion, not a user access token. The
assertion binds the actor/account/rule/epoch/grant, exact target, operation,
audience and request digest. Device-service validates it on a private capture
route, then renews the actor's live account/source authority through its SQL
owner. Analytics requires the source in the sealed compiled graph and reads
the deployment again after the response. A missing secret fails closed.
The same exact-body assertion also covers bounded source observation pages and
coverage proofs. Device-service reuses its signed owner path, which checks the
source before and after telemetry I/O; analytics pins source/schema/generation
and rechecks deployment state after the response. These reads grant no runtime
or effect authority by themselves. A separate signed dataset-description path
renews the actor's current account and dataset ownership, and analytics checks
the `lookup_asof` target and sealed dependency digest before returning it.
The private machine manifest, point and finite-split paths read only an
existing sealed native as-of snapshot. Device-service verifies the exact POST
body, actor-owned account/dataset and current schema, scope and generation
around the SQL snapshot query. Analytics requires an ACTIVE deployment, its
sealed `lookup_asof` target/projection and renewed grant/dataset authority,
then compares the returned manifest and deployment after owner I/O. Snapshot
sealing and durable selection for an autonomous worker remain under 5.17.
For a durable window containing `lookup_asof`, staging must persist the
canonical SNAPSHOT query, interval/key-field selection and exact owner manifest
for each declared lookup node with the keyed recipe. The snapshot IDs and
manifest digests join the window's immutable pins; replay may only use those
original identities. Recovery rechecks the sealed bundle, current dataset
grant/scope and manifest identity through the scoped machine owner before
reading rows. Event-time and finite-split reads must use the same pinned
manifest and fail closed on a changed generation, data version, projection or
scope. A fresh current snapshot cannot replace the staged one after restart.
The existing direct-journal diagnostic profile has no such persisted selection,
so it rejects lookup graphs before timer consumption.
The reviewed worker records reference-based aggregate and interval window
assignments as REKEY outputs. Each assignment binds the source event digest,
checkpoint, generation and target key. After renewing owner authorization,
the worker consumes the assignment under the active deployment fence and
commits the target window state, timer and source acknowledgement together.
The journal cursor advances after the assignment is consumed. Restart can
resume from a retained assignment, and cutover waits for predecessor
assignments to be consumed before transferring the active slot.
Window preview and covered finalization use the same reducer for direct and
reference-backed inputs. An unresolved filter or subject makes the result
UNKNOWN when the row could contribute to that subject's window. A known
different subject and a row excluded by the filter leave the result alone.
The reviewed rule API exposes a read-only comparison at
`POST /analytics/configurable/rule-cutovers/window-comparison`. The request
names the sealed bundle, operator, typed subject, window and two sets of
sealed lookup queries. It evaluates both sets at one source journal cut with
the same reducers used by the worker. The response includes the source cut,
reference manifest digests, input digests and local outcomes. The comparison
does not publish a corrected result or change an alert.
The historical variant at
`POST /analytics/configurable/rule-cutovers/historical-window-comparison`
accepts a deployment epoch, stored final measure and corrected lookup
queries. It restores the original subject, window, source checkpoint,
coverage proof and reference manifests from the deployment and measure.
It verifies that replay reproduces the saved input digest and local result
before evaluating the corrected manifests over the same source cut.
The historical comparison returns a confirmation digest. Posting it with the
same request to `/analytics/configurable/rule-cutovers/historical-window-corrections`
repeats the owner reads and saves the confirmed comparison as an immutable
record linked to the original measure. Identical confirmation reuses the
correction ID. Publication preserves both outcomes. An explicit
`updateAlert=true` applies a known corrected decision to the existing
occurrence. The keyed transaction checks that the original is still the
latest measure and that the window has no pending timer. It appends the
corrected measure, alert proposal and receipt link together. The proposal
uses the existing occurrence identity and Alert Service authority checks.
A later correction scheduled for that key uses the last confirmed reference
override. A newly accepted observation also schedules overlapping corrected
windows in the same deployment. For interval reports, this uses the report's
retained start and end fields. Each deployment admits up to 128 distinct
windows with an applied reference correction.
Publishing an alert correction increments a deployment-local reference
revision in the same transaction. The journal cursor checks that revision
before advancing, so a worker that raced with publication retries the event
against the new set of corrected windows.
Before applying an alert correction, Analytics also scans the window's
retained input range after the reviewed checkpoint. If newer observations
are present, it saves a follow-up timer with the corrected measure and
reference pins. If the journal cursor advances past the scanned head before
commit, publication returns a conflict and a retry scans the new range.
A bounded read-only admission scan can now discover ACTIVE deployment
candidates, renew their graph-declared source, dataset, grant and policy owners
with machine identity, and reject a deployment changed during those reads.
It reports the source's conservative runtime-readiness flag without consuming
timers. The current capture contract reports `ruleExecutionReady=false`, so
candidate discovery cannot promote an unqualified source into execution.
The machine source adapter can reconstruct the original `SourceCaptureSnapshot`
from a stored journal cut. It reuses the cut digest and captured checkpoint
checks, while its refresh, page and coverage callbacks go through scoped
machine owner reads. The bearer-based due-timer reconstruction has not yet
been switched to this adapter or admitted for scheduled execution.
A direct-journal diagnostic window can now be reconstructed from its durable
recipe against the ACTIVE sealed deployment. The machine path checks exact
account/rule/epoch, canonical rule document, source and policy declarations,
original cut and recomputed keyed-state pins. It refuses lookup nodes without
a prepared snapshot. This reconstruction is callable infrastructure; the
scheduled due-timer loop remains gated by coverage readiness and 5.17.
An opt-in scheduled diagnostic drain now discovers bounded ACTIVE candidates,
queries due timers by exact account/rule/deployment epoch, renews machine
admission, reconstructs each window and consumes through the existing keyed
lease/CAS transaction. Ordered deployment pages rotate across scans, so a
bounded first page cannot permanently starve later candidates. Owner failures
leave timers pending. Outputs remain
unfinalized MEASURE records; no alert effect is dispatched. The default
`app.vcl.worker.enabled=false` keeps this scheduler inactive until full
coverage, restart, revoke and competition acceptance qualifies 5.17.
The worker instance uses one lease holder identity across polls; restart uses
a new holder and must wait for an unexpired prior lease before claiming work.
Within a deployment, discovery filters for WINDOW_CLOSE in SQL and advances
by due time, key digest and timer ID even if a candidate could not be consumed.
The next pass starts from the beginning after reaching the end, so pending
timers can be retried. The worker caches up to 128 deployment cursors locally.
Eviction or restart resumes discovery at the beginning of that deployment.
Machine consumption renews remote owner checks before writing and locks the
exact ACTIVE deployment row in the keyed SQL transaction before locking the
window. Revocation and measure commit therefore serialize on the deployment
row. A revoked or changed deployment leaves the timer, inbox and output intact.
The worker also compares the discovered schedule with the one acquired under
the lease. Remote owner checks take place outside the local SQL transaction.

V043 adds the diagnostic window cutover transaction. It locks the two epochs
in a stable order, asks the authority callback to verify the successor and
boundary checkpoint, moves the active slot and records the predecessor,
boundary and checkpoint digest. The predecessor retains drain authority for
persisted windows opened before the boundary. The successor accepts windows
starting at or after the boundary. Worker recovery checks those limits and the
keyed commit fences the exact epoch revision. Explicit revocation clears
drain authority in the same row transaction. The reviewed activation caller
and qualified source checkpoint producer still need to be connected.
The caller-authorized JDBC journal path also locks an existing deployment
before a window transition. A new timer requires ACTIVE and a window start
within that epoch's boundaries. A draining epoch can consume an existing
timer for an eligible window. Standalone diagnostic epochs have no
deployment row and retain their existing behavior.

For window and zone sessions, the draining epoch continues to read its pinned
journal while it has session or coverage-wait timers. It routes observations
only to committed occurrences. The keyed transaction checks the original
opening time against the cutover boundary. When the successor encounters the
same subject and occurrence, it skips opening a second session and the keyed
transaction independently checks the predecessor's claim.

The captured RULE snapshot can build the source component of the start
checkpoint. It requires one fresh COVERED owner proof per exact source schema
target, covering the requested interval through the cutover boundary. Proofs
must belong to the pinned generation and fit inside the retained cut. The
digest includes the canonical RULE, owner scope and ordered per-source proof
and checkpoint identities. The combined start checkpoint adds the compiled
semantic digest and one sealed native SNAPSHOT query per lookup node, with
its interval field and owner manifest digest. Verification rebuilds from the
original journal cuts and snapshot IDs under fresh authority. V044 stores the
canonical credential-free replay recipe in the transaction that enters
ACTIVATING. The predecessor keeps the active slot while readiness is pending.
V046 records journal window openings in the keyed transaction. Request and
completion verify that the predecessor has no window at or beyond the
proposed boundary. A pending successor reserves that boundary under the same
predecessor row lock used for window admission. Before the boundary the
predecessor can still open windows, and revoking the pending successor
releases its reservation. For legacy rows without an opening timestamp,
cutover reads up to 128 retained, integrity-checked window recipes and
requires an explicit recovery path for unsupported or larger histories.
The cutover coordinator binds the recipe to the successor's sealed RULE
document and renews exact owner evidence before changing either epoch. V045
requires a durable evaluator ACK for the exact successor epoch, bundle and
checkpoint. Only an ACTIVATING successor can receive a new ACK, and the
diagnostic worker cannot execute it before the slot transfer. The evaluator-side
write is immutable and the cutover coordinator
does not accept an ACK from its caller. The opt-in worker now produces the
ACK for a direct journal aggregate/compare graph after restoring the exact
checkpoint, verifying a qualified proof and compiling the window, timing
and terminal decision plans. Lookups and other graph profiles stay pending.
The ACK covers evaluation readiness for this profile. A reviewed activation
dispatch and alert effect admission remain open.

Alert-service V023 owns cutover consent. Preparing a review pins the rule,
grant epoch, predecessor and successor, sealed bundle and checkpoint digest.
The authenticated owner confirms that exact review digest under a fresh
grant check. Confirmation is durable, idempotent within its lease and
separate from applying the change in analytics.

Analytics exposes checkpoint preview and reviewed cutover apply under
`/analytics/configurable/rule-cutovers`. Apply reloads the confirmed review
from alert-service using the signed actor bearer, checks the sealed bundle's
grant epoch and exact checkpoint, then renews confirmation under the
deployment transaction. Reapplying the same request after the evaluator ACK
finishes the slot transfer. An awaiting evaluator returns ACTIVATING.
For initial activation, the reviewed intent has a null predecessor. Alert
V024 and analytics V048 preserve that distinction. The same checkpoint and
ACK checks apply, and the unique active-slot index resolves concurrent first
activations. The flow requires a sealed successor bundle.

V051 records a logical-rule pause on the active, draining and activating
epochs. Window opening and effect delivery stop under the same deployment
lock. The opt-in local sweep removes unfinished window timers and appends an
UNKNOWN(PAUSED) measure and control receipt without alert proposals.
Resume requires a newly reviewed epoch and checkpoint after the pause,
with window admission left-censored at the database activation time so the
paused period does not produce catch-up alerts. Alert-service V028 persists
pause reviews with exact epoch, deployment revision, grant and bundle pins.
The authenticated actor confirms the review digest. Analytics renews that
confirmation under the deployment lock before applying the pause. A pause
requires current rule-owner access and can stop an epoch after its execution
grant is revoked. The pause sweep also closes persisted session timers.

The window session kernel compiles start and close predicates through the
shared typed scalar evaluator and checks both subject and occurrence on each
observation. An actual close can complete an observed start. A close with
missing start, an ambiguous boundary, timeout and pause have distinct
unknown outcomes. Late observations require correction rather than mutating
the live occurrence. DurableWindowSession commits the typed state, timeout
schedule, exact input receipt and terminal measure in one keyed transaction.
Timeout consumes the precise scheduled revision, and pause clears the timer
under the paused deployment lock. The reviewed epoch worker routes journal
observations to the direct window_session plan using exact subject and
occurrence identities. The plan scope comes from the original start cut and
does not move as the input head grows. Session timeout uses root lateness,
fresh owner checks and the exact deployment and keyed transaction fences.
The circle zone kernel shares the scalar WGS84 distance/error calculation.
It applies the configured accuracy floor, starts only on an observed
outside-to-inside transition and closes only after the required number and
span of actual outside observations. Missing accuracy and boundary
uncertainty cannot supply witnesses. Source gaps, geometry changes,
timeout and pause have explicit unknown outcomes. DurableZoneSession uses the
same observation and timer transaction protocol as DurableWindowSession.
It persists the selected geometry revision and the boundary witnesses, so
restart can recover the exact plan and the pause sweep can close either
profile. The device-service circle
owner publishes exact immutable geometry revisions. The analytics worker
adapter reads a circle only when its identity, revision and digest are
pinned by the sealed bundle and the grant and deployment remain current.
Explicit `point` and `spatial_ref` source mappings now preserve WGS84
coordinates and typed feature UUIDs in the accepted journal. Both have
schema-bound validation, and a reference in an observation supplies no
geometry authorization.
The direct zone_session worker routes those fields by exact subject and
occurrence. It resolves only sealed circle pins and retains the first
persisted pin for subsequent samples and timeout recovery. A changed current
revision is returned as unavailable for membership and closes the unfinished
session UNKNOWN without adopting the new circle.

The direct-journal session absence evaluator pins the exact session plan,
including the circle snapshot for a zone session. Its range read includes
the actual entry and close observations. The close observation can end a
bounded hold at the exclusive boundary. A negative decision requires an
event-time coverage proof through the root lateness horizon, at a cut that
includes the proof checkpoint, and continuous observations for the exact
subject and occurrence. An unresolved identity prevents a negative
decision. A later checkpoint with an observation that could change the
closed occurrence requires session correction before producing a decision.
Direct window and zone sessions accept a chain of filters over the source
journal. Live routing and replay use the same compiled predicates. A
potentially matching observation with unknown filter eligibility prevents
confirming absence at the evaluated checkpoint.
When a late observation with an exact occurrence key has unknown filter
eligibility, it can schedule a correction for that existing session. The
observation leaves the saved boundaries intact and cannot start a session.
The correction records UNKNOWN when the filtered history cannot confirm the
earlier result.

For a direct session graph, the worker atomically saves the close checkpoint
and a keyed wait timer with the session outcome. The due worker discovers
owner coverage, reopens the pinned session and source, and consumes the
timer together with the absence measure. Coverage still pending leaves the
timer due. Pause removes the wait and records an unknown measure. The
worker can also end a due wait as UNKNOWN after the root idle timeout,
using the latest accepted journal row and the execution database clock.
The idle result carries no coverage or effect authority.
Scheduled aggregate and interval windows can also close an overdue wait as
UNKNOWN after source idleness. An early probe alone cannot close the wait.
The idle measure preserves any earlier positive alert basis and can be
superseded by a later report.
Sustained candidates also finish as UNKNOWN when the root lateness
horizon has passed and the input journal is idle without qualified
coverage. The result consumes the candidate without advancing a
watermark or changing an alert.
The pause worker also closes pending sustained measures as UNKNOWN and
acknowledges them atomically while holding the paused deployment fence.
The old cursor stays with its epoch; resuming uses a new epoch.

Configurable rule matches can enqueue notifications through the existing
Alert Service port when the account's default notification preference
explicitly enables configurable-rule alerts. The default is disabled.
The delivery worker rechecks the opt-in and existing channel preferences
before delivery. Receipt replay, retraction and reopening do not enqueue
another creation notification.

The worker replays late samples for window and zone sessions in event-time order,
with bounded history before entry and the deployment start or resume as a
lower fence. When replay moves entry earlier, the worker requests coverage for
the expanded interval and waits for a cut that includes the late observation.
An unknown window-session predicate that can change a boundary closes
the occurrence as UNKNOWN. An unknown start predicate in an already open
occurrence does not override a known close decision.
Before a window session closes, an out-of-order observation can replay
its bounded journal history at that observation's checkpoint. The new
state, timers and input receipt commit together. Geozone replay uses the
same bounded history read and the current pinned circle revision. It
requires the trigger and the previous endpoint to be present at the cut.
An open or left-censored window session also replays when two observations
share an event timestamp. Both the live replay and absence evaluation order
ties by observation UUID. Replay checks that the previous start witness, when
known, is still present in the retained history.
If an active session already timed out, an observation whose event time
precedes the deadline can trigger replay at its journal checkpoint. A replay
with an observed close appends a corrected outcome and starts the normal
coverage wait. Otherwise the timeout remains UNKNOWN. The original timeout
outcome stays in history.
When the corrected session remains open at the original close, the worker
looks ahead at the current journal head within the maximum session duration.
A new close is provisional until a coverage cut includes the relevant
observations and replay confirms the corrected interval. Range reads are
split at the owner interval limit under one byte and observation budget.
The measure records the late observation, evaluated checkpoint and actual
boundary witnesses. A later observation for the same occurrence at or before
its recorded close schedules another evaluation with the original closing
checkpoint and a minimum checkpoint for the correction. Each completed
evaluation appends a measure linked to the preceding measure by its output ID.
This path needs an original closing checkpoint in the preceding measure.
If a direct emit_alert consumes a confirmed absence, Analytics atomically
appends its alert proposal with the measure. Alert Service validates the
session profile and re-reads the owner coverage proof before accepting the
proposal. Deduplication uses the typed subject and occurrence digests, so a
corrected boundary does not create a second alert for the same occurrence.
A corrected measure with a confirmed witness can append a retraction proposal.
The outbox delivers effects in keyed revision order. Alert Service resolves
the existing alert and appends a retraction event, evidence and receipt in
one transaction. Unknown results do not trigger retraction. A later corrected
absence can reopen the same alert after checking maintenance suppression and
matching its current resolution to the automatic retraction event. The owner
appends a reopening event and preserves the original alert identity. Replay
beyond the bounded history remains pending.
For positive-duration witnesses the worker resolves the optional versioned
sample hold from the owner binding revision and caps it at the root coverage
gap. Older binding definitions without the contract remain readable.
The start worker acknowledges a direct single-source session graph only
after compiling the session, witness, absence and direct alert chain against
the reviewed checkpoint. Zone sessions also require current owner geometry
for every sealed circle pin. The acknowledgement binds the bundle, start
checkpoint and evaluator plans. Other session graph shapes remain pending.

The direct-source sustained plan compiles the typed predicate and binds the
source revision's sample hold, operator gap, subject identity and root timing.
It evaluates a bounded supplied observation batch through the shared witness
kernel. The journal preview reads an exact bounded source cut with an
end-boundary sample, conservatively handles unresolved subject identity
and returns cut and scoped input digests. Each separate run reports its first
observation and the actual endpoint that first proves the minimum duration.
The occurrence digest uses account, rule, node, typed subject and the first
observation. It is stable across repeated reads that include the same run
start. The journal worker has a durable per-subject cursor that preserves the
run across batches and records threshold crossings with its input receipts.
It seals a timestamp group after the next distinct timestamp so equal-time
observations yield a deterministic anchor. A late observation causes the
worker to replay that subject from its earliest admitted sample through its
pending timestamp at the input checkpoint. The cursor and a separate
correction measure commit together. Replay is bounded by row and byte
budgets and requires resolved subject identity. A threshold measure pins
the input generation and checkpoint. The finalization path looks up owner
coverage through root lateness, checks the watermark, and reproduces the
same episode and witness endpoints at the proof cut. The due worker scans
pending threshold measures and atomically acknowledges each together with
its final measure and any direct alert proposal. Alert Service verifies the
event-time proof and deduplicates by the sustained episode identity.
The start worker acknowledges only the direct read_stream, sustained,
emit_alert chain with qualified start coverage, an owner hold contract and a
supported terminal policy. The acknowledgement binds the exact journal cut,
timing and witness plans. An unassigned observation does not open a cursor.
Finalization still checks unresolved subject identity in the evaluated
interval. Late-input replay compares the episode sets with and without the
trigger observation. It appends new or changed episodes to the finalization
queue with the correction checkpoint and records removed episodes in the
correction measure. Removed episodes are also queued for covered replay.
A retraction requires that replay no longer reproduces the occurrence and
reports no unresolved evidence. Analytics finalizes candidates in keyed
revision order. Alert Service can resolve the prior alert or reopen an
automatically retracted alert when a later correction confirms the episode.
Retraction remains bounded by the supported owner proof and replay interval.

Machine checkpoint restoration loads the stored recipe and sealed bundle, renews
grant, policy, journal coverage and native manifests, and compares the resulting
checkpoint digest and deployment revision. ACTIVATING permits those read-only
checks. Observation pages, as-of row reads and timer execution still require an
executable epoch. No user bearer is stored for this recovery path.

A measured journal window with COVERED evidence persists the exact private
proof reference, generation, source position, interval, required checkpoint
and retained cut digest with its result. A second owner read after the final
authorization check rejects a proof that changes before the keyed commit.
The diagnostic measure remains unfinalized and cannot yield an evaluator ACK.
Telemetry V023 records the database acceptance time of newly attested
coverage. Old attestations retain coverage semantics and carry no acceptance
time. The measured journal window uses fresh timed coverage to feed the
bound source's trusted frontier into the root timing plan. Unproven inputs
remain UNKNOWN, and root lateness is subtracted once. The result records a
candidate watermark tied to its evidence, separate from durable monotone
frontier advancement and finalization.

V047 adds a durable diagnostic frontier per deployment, stable timing and
authorization scope, source schema, input generation and producer epoch.
The measured-window transaction advances it under the deployment lock only
when timed evidence extends the current contiguous interval. An older result
cannot regress the frontier. Disjoint evidence stays with its individual
measure and does not advance the shared frontier across a gap. Standalone
diagnostic epochs have only their per-window candidate. Finalization and
effect authorization remain separate.

The machine close path for a reviewed successor requires fresh timed coverage
for the full window, a watermark at its end or later and an attested event-time
basis for that interval. The keyed transaction checks the resulting contiguous
durable frontier before committing the finalized measure. Failed checks keep
the timer pending. Telemetry V024 lets the configured coverage attestor
explicitly assert `SOURCE_EVENT_TIME_V1` for a producer prefix and interval.
This asserts that its event timestamps are authoritative for interval
membership in that statement. Telemetry renews the existing generation,
sequence continuity and contradiction checks. The basis is immutable for a
proof ID and is carried through signed owner reads. Unqualified statements
retain ordinary coverage semantics and cannot advance the qualified frontier.
The basis qualifies only the covered interval. Source-wide readiness, the
evaluator ACK and alert effects retain separate gates. Diagnostic epochs keep
their existing unfinalized behavior.

V050 gives each active reviewed direct-window epoch a durable journal cursor.
The opt-in opener reads up to eight observations per epoch per poll. It
processes each against its own checkpoint, validates its subject and time,
and stages work with a stable identity independent of journal head. The
cursor advances one observation at a time after that observation's eligible
work is durable. If another worker advances the cursor first, the opener
ends the page and reads the durable cursor on the next poll. A session
timeout pins the journal generation and head on its first due attempt.
Retries and replacement workers wait for that same checkpoint while the
timer revision remains current. The timeout transaction checks the pinned
generation and processed checkpoint before writing the result. An already captured exit can be applied
before the timeout. The retained
prefix and fan-out limits still apply. At close an automatically opened timer asks the
device and telemetry owners for a qualified proof covering the window and
root lateness in the original generation. Discovery returns a single proof ID
or persists an immutable composite of up to 64 qualified statements with no
gaps and one producer epoch. Close renews the exact proof and every composite
part before commit. The proof ID and component identities are persisted with
the result and do not change the staging identity. The profile admits calendar,
fixed and rolling windows whose conservative duration plus root lateness fits
within the 34-day lookup bound. Calendar readiness uses the compiled window's
local day count and the offset range of its pinned timezone rules. A longer
elapsed month at a DST transition uses the same bounds for assignment and
coverage planning.

Reviewed window close can replay a newer prefix when its coverage proof
requires a checkpoint past the staging cut. The source snapshot, schema and
input generation must remain unchanged. Replay stops at the proof checkpoint
even when more observations have arrived. It uses the existing typed reducer
with a 4096-row and 8 MiB retained-input limit and persists the resulting
measure, decision and evaluated cut with the timer receipt. The original
staging recipe stays available for historical reconstruction.
Telemetry V026 supports signed half-open event-time range pages at an exact
checkpoint. Reviewed staging and close read only observations in their
window while preserving canonical row and receipt integrity checks. The
4096-row and 8 MiB bounds apply per window, independent of the total journal
length. The opening cursor and legacy diagnostic reads retain their
contiguous checkpoint semantics.

An explicit stored source mapping of type `decimal` accepts decimal text and
normalizes it without a binary floating-point conversion. Device publishes
the mapped field as VCL DECIMAL with a registered unit. Telemetry preserves
the canonical decimal string and the unit in the accepted journal, and the
window reducer receives the same typed literal. Legacy `number` mappings keep
their existing behavior and require a new explicit mapping to supply exact
RULE inputs.

A finalized TRUE decision from the reviewed direct-window profile writes
an immutable ALERT_PROPOSAL alongside the measure in the same keyed
transaction. The proposal pins the bundle, grant epoch, policy, covered
window and measure digest. Its business dedup digest uses rule, subject and
window bounds, independent of deployment epoch. It carries private evidence
references rather than raw readings. The alert owner accepts exact-body scoped
machine proposals, renews the grant, policy and source proof, and serializes
the local grant and policy checks with alert and receipt persistence. For a
new direct-window proposal, profile `VCL_WINDOW_ALERT_PROPOSAL/2` carries both
the evaluated cut digest reference and the source owner's event-time proof
digest. Analytics binds the proof digest to the stored measure. Alert Service
compares it to the freshly resolved proof. Previously queued version 1
proposals retain their original bytes and admission checks for receipt replay
and delivery. In a rolling upgrade, deploy the Alert Service reader before
the Analytics writer.

For a new effect, Alert Service reads the source proof while holding the local grant and policy
locks, then rereads the source to verify that its generation and binding
snapshot still match and that the journal head has advanced monotonically.
Grant and policy expiry are checked after these owner reads and again after
staging the effect. Account,
rule, subject and occurrence dedup reuses the original alert across epochs.
The analytics delivery worker scans bounded pages of unacknowledged proposals
and checks the exact reviewed epoch and owning finalized TRUE measure under
deployment and keyed row locks. It sends the immutable bytes with a scoped
machine assertion and records only the matching owner receipt. The keyed
store accepts alert proposal acknowledgements through this delivery path.
Local rekey consumption and sustained finalization each check their expected
output kind before recording their acknowledgements. Lost responses
leave the local proposal pending for an exact retry. Delivery is independently
opt-in via `app.vcl.worker.alert-effects-enabled`. Alert-service V026 writes
private coverage and measure references with the effect receipt and stores
the policy retention deadline. A bounded scheduled cleanup deletes expired
references while retaining the receipt. A new alert also records one
CONFIGURABLE_RULE_MATCHED lifecycle event without private evidence in its
public context. V027 applies the existing maintenance suppression policy to
the covered device and window before opening an alert. An immutable occurrence
outcome prevents a retry or a new epoch from reopening a suppressed occurrence.
The owner receipt uses a null alert ID for suppression. Occurrence outcomes and
receipts survive alert retention, while device history purge removes private
references under the same local device deletion fence used for admission.
The receipt-only operation authenticates the original proposal bytes and
grant owner identity even after revocation. It returns a stored receipt or
404 and has no write path. A revoked deployment is eligible only for this
operation and settlement. An uncertain active send can also reconcile a lost
ACK. If the receipt is missing, the separately signed settlement operation
locks the same alert-owner account and grant as effect admission. It returns
the existing receipt if one was recorded in the meantime. Otherwise it
returns a stable no-effect acknowledgement for a revoked grant epoch, an
expired exact grant or its expired exact evidence policy. Analytics can then clear the pending proposal and
process subsequent proposals for the key. A paused deployment with an active
grant and no receipt still has no terminal acknowledgement.
The alert owner additionally accepts a separate scoped grant assertion. It
reads the effective grant from SQL, reproduces the original source/dataset
owner pins through short-lived device assertions, and rereads the grant and
account. Analytics compares the returned identity, epoch, status and definition
with the sealed grant revision digest and rereads deployment state. Every
machine source and dataset metadata read now renews this grant around its
owner response. The policy owner accepts a distinct scoped assertion, renews
grant and policy SQL state across owner I/O, and returns only a usable policy.
Analytics requires the `emit_alert` policy in the sealed graph and compares
its identity and revision digest. Scheduled execution remains gated by 5.17;
effect-time permission is still separate.
