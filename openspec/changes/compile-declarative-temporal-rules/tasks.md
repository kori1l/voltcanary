# Implementation and Verification

- [ ] 2.1 Implement VCL runtime signatures, bitemporal evidence/corrections, grant fencing and deployment cutover; prove all related A01–A66 invariants.

- [ ] 1.1 Specify canonical plan/operator types, versioning, three-valued logic, as-of joins and execution budgets.
- [x] 1.2 Implement the selected pure VCL scalar kernels, exact arity/type/unit/null semantics and bounded compiler checks without changing numeric AST v1.
- [ ] 1.3 Implement compiler, pure generic operators and shared dry-run/production evaluation.
- [ ] 1.4 Implement durable sessions/windows/timers, readiness/checkpoint and correction/outbox/inbox lifecycle.
- [ ] 1.5 Integrate existing alert/suppression/notification ports and prove cross-domain independent outcomes.
- [ ] 1.6 Run focused domain/security/PostgreSQL resilience and repository checks; document tested limits.


- [ ] 3.1 Implement timing coherence, observed exits, local-month bounds, pause/resume and A59-A62 deterministic replay tests.

## 103. Bus scenario expressibility and evidence

- [ ] 103.1 Prove minimumDurationUs=0/non-vacuous witness semantics and concrete executable stable occurrence input binding; reject unsupported fan-out/expectation triggers instead of hiding an HSL-specific evaluator (BUS01/BUS03/BUS06).

## 4. Owner identity prerequisites for G0

- [x] 4.1 Implement signed account-scoped RULE reservation/inspection, immutable reserved output/receipt IDs, database-clock expiry, replay/conflict/quota fences and H2/PostgreSQL concurrency tests.
- [x] 4.2 Implement reviewed alert-owned automation grants, exact rule/source/dataset/field scopes, current owner checks and private inspection; source metadata alone must not assert capture or evaluator readiness.
- [ ] 4.3 Implement grant epoch revocation and fresh-owner authorization at the atomic alert/effect boundary, with replay/expiry/concurrency evidence and no implicit notification authority.
- [x] 4.4 Implement a reviewed private-reference evidence-policy owner with immutable IDs/receipts, exact grant epoch, explicit retention/reference budgets, fresh owner validation and H2/PostgreSQL replay/uncertainty/concurrency checks; metadata must not create evidence or activate rules.

## 5. Pure temporal execution prerequisites

- [x] 5.1 Implement immutable versioned window-assignment plans from compiled window_aggregate nodes: exact microsecond FIXED/ROLLING alignment, bounded fan-out, local DAY/WEEK/MONTH boundaries and pinned timezone rules; verify pre-epoch/boundary/DST/calendar/range cases without claiming aggregation, completeness or durable runtime.
- [x] 5.2 Implement a pure bounded interval-union/exclusion kernel with exact half-open microsecond clipping, no double-counting, explicit UNKNOWN/open/null/failure handling, scope/version fences and independent interval/DST/correction vectors; supplied-batch arithmetic must not assert source/reference completeness or activate rules.
- [x] 5.3 Implement a pure bounded typed window-reduction kernel for COUNT/SUM/MIN/MAX/AVG from shared compiled IR, with exact half-open membership, checked units/precision, explicit empty/null/unknown behavior, scope/version/plan fences and independent arithmetic/calendar/correction vectors; supplied-batch results must not assert finalization, completeness or emission authority.
- [x] 5.4 Implement a pure pinned root timing plan with exact future-skew quarantine, minimum required source/reference progress minus root lateness once, explicit unknown/missing progress and idle expiration; prove boundary/range/replay cases without claiming coverage, observed exits, durable timers or finalization authority.
- [x] 5.5 Implement isolated JDBC keyed-state/inbox infrastructure with immutable registration pins, database-clock lease fences, CAS revisions and atomic input receipts; verify restart, replay/conflict, lost ACK, rollback, account/key isolation and H2/PostgreSQL lock races without claiming a complete durable evaluator.
- [ ] 5.6 Extend the keyed transaction to bounded durable timers, derived/rekey outputs and outbox acknowledgements, then integrate actual generation/owner/deployment/coverage fences and evaluator transitions; state/inbox alone must not qualify durable RULE execution.
- [x] 5.7 Implement atomic bounded timer-snapshot storage/discovery/consumption with exact schedule revisions, input namespace separation, database-clock due/lease guards and H2/PostgreSQL restart/correction/rollback/race evidence; timer metadata must not establish observed exits or finalization authority.
- [x] 5.8 Implement bounded immutable derived/rekey/alert-proposal outbox metadata in the keyed state/timer/inbox transaction, with exact output reuse/conflicts, scoped target identity, fenced immutable receipt acknowledgements and H2/PostgreSQL uncertainty/rollback/concurrency checks; no sender or external effect authority may be inferred from stored output/ACK metadata.
- [x] 5.9 Implement atomic local REKEY consumption into a supplied target state/timer/output transition and source acknowledgement, with source-qualified input identity, immutable verified local receipts, ordered dual-key leases/CAS, retained budgets and H2/PostgreSQL replay/rollback/concurrency checks; preserve separate evaluator and owner-authority admission gates.
- [x] 5.10 Implement a shared pure bounded duration-witness kernel for compiled observed_within/sustained nodes, requiring actual zero-duration witnesses and explicit bounded hold for positive duration, exact partition/time/plan fences, gap/unknown/false handling and independent arithmetic vectors; retain coverage/finalization/durable evaluator gates.
- [x] 5.11 Integrate typed duration-witness batch transitions with keyed SQL state/inbox/output intents, immutable original-result replay, bounded correction state, integrity-checked input/output receipts and legacy-preserving migrations; verify H2/PostgreSQL uncertainty, races, rollback and corruption without qualifying autonomous owner/coverage/effect admission.
- [x] 5.12 Reject malformed snapshot pins and multiple revisions of the same required input before building the pure timing plan.
- [x] 5.13 Stage a bounded prepared journal window in keyed SQL with its due timer. Consume the timer into one immutable diagnostic measure, preserve replay after reconstruction and recheck access before output disclosure.
- [x] 5.14 Execute the compiled compare node in the direct journal window graph. Persist true, false and unknown decisions with the measure, pin terminal identity, and keep alert dispatch outside this profile.
- [x] 5.15 Recover a staged window from private SQL state with fresh signed authorization and the exact original journal cut. Verify replay after journal growth and reject cut regression.
- [x] 5.16 Drain a bounded account-scoped set of due WINDOW_CLOSE candidates through historical reconstruction and fenced consumption under fresh caller authority. Verify wrong-account isolation, repeat drain and revoked-grant refusal; autonomous scheduler identity and deployment admission remain open.
- [ ] 5.17 Wire a scheduled analytics worker to durable active deployments with a scoped service identity and fresh owner, grant, generation and proof reads. Never persist or reuse a user bearer token; prove restart, revoke and competing-worker behavior before activating RULE execution.

The diagnostic worker now filters WINDOW_CLOSE before limiting its SQL page and
continues past failed candidates within a deployment. It retries pending
candidates after wraparound and retains up to 128 local deployment cursors.
The focused run passed 163 tests, including H2 and PostgreSQL cursor cases and
worker failure/retry (`artifacts/g4-window-pagination-tests.log`). Coverage
qualification and multi-process worker acceptance remain open.

- [x] 5.18 Persist immutable staged/active/revoked RULE deployment identities and a one-active-epoch fence with sealed bundle identity checks. Keep this isolated from worker/effect activation until 5.17 authority and recovery gates exist.

Deployment registry checkpoint 2026-11-06: V040 and `JdbcRuleDeployments`
pass eight focused H2/PostgreSQL cases for immutable epoch replay/conflict,
fresh callback rollback, one active epoch, revoke, restart reads, tenant scope
and bundle/grant corruption rejection, plus activation/revoke lock ordering
(`artifacts/g4-deployment-focused-20261106.log`).
The registry is not a Spring bean or public activation endpoint. A caller-supplied
authority callback is an infrastructure precondition, not a service identity;
task 5.17 remains open.
The existing Compose analytics database upgraded from V039 to V040 through
Flyway and the service returned HTTP 200 health after restart.
- [x] 5.19 Add a separate short-lived scoped machine assertion and the first
  source-capture owner read for an ACTIVE deployment. Pin actor/account/rule/
  epoch/grant/target/request and renew deployment state after the owner response;
  leave scheduled execution and the remaining owner reads gated by 5.17.

Worker source checkpoint 2026-11-06: V041 stores the immutable actor on new
deployments; V042 revokes pre-actor active rows and prevents their reactivation.
The device private capture route
accepts a 30-second signed exact read and still checks current actor/source
ownership in SQL. Analytics loads the ACTIVE deployment and sealed bundle,
requires the declared read_stream target, signs only that request, validates
the owner response and re-reads the deployment. Two assertion tests, two
device boundary tests and eleven analytics/registry tests pass; the HTTP test
revokes the deployment during the owner response and rejects disclosure.
`artifacts/g4-worker-source-focused-20261106.log` and
`artifacts/g4-worker-source-analytics-20261106.log`. Compose/Kustomize config
passes. Flyway applied V041 and V042 over the existing Compose V040 schema and
analytics returned HTTP 200 health. The machine secret defaults empty and no
autonomous worker is active.
`python scripts/vcl-worker-source-docker-smoke.py` also passed against the
real device owner: a valid exact machine read succeeded, changed revision and
ordinary user bearer alone returned 401, and device decommission caused the
next signed read to return 404. The script restored the Compose device-service
environment; its machine key was empty and health returned 200 afterward.
- [x] 5.20 Extend the scoped machine source owner path to bounded accepted
  observation pages and coverage proofs, with exact signed request bodies,
  generation/schema checks and deployment recheck after the owner response.

The focused device boundary suite rejects changed page bodies/proof targets
before owner I/O. The analytics HTTP suite reads one typed accepted page and
an UNKNOWN proof, then revokes the deployment during the next page response
and rejects it. `artifacts/g4-worker-source-focused-20261106.log` and
`artifacts/g4-worker-observation-focused-20261106.log` pass. Real Docker
machine-page acceptance, grant/policy/dataset machine reads and an autonomous
scheduler remain to be completed under 5.17. The real device-owner Docker
smoke now also accepts a signed coverage request, returns UNKNOWN for an
unavailable proof, rejects changed body/target with 401, and returns 404
after device decommission. The temporary key is removed on restoration;
`artifacts/g4-worker-source-docker-20261106.log` passes.
- [x] 5.21 Add a scoped machine dataset-description owner read for a sealed
  `lookup_asof` dependency, with current actor/account authorization, exact
  request signature, dependency digest and post-response deployment fence.

The private device boundary test checks exact dataset/revision binding and
rejects changed targets. Analytics rejects unpinned datasets and a valid
but schema-drifted native owner description under a signed request.
`artifacts/g4-worker-dataset-device-20261106.log`
and `artifacts/g4-worker-dataset-analytics-20261106.log` pass. A real Compose
owner round trip creates a native dataset, reads its description with a scoped
machine assertion, rejects a source assertion on the dataset path, and restores
the empty-key configuration (`artifacts/g4-worker-dataset-docker-20261106.log`).
Signed row reads and policy machine checks remain under 5.17; this metadata
path does not start a worker.
- [x] 5.22 Add a scoped machine grant owner path that reloads the effective
  grant, reproduces its original source/dataset pins with machine owner reads,
  checks actor/account/rule/epoch, and rereads grant state after owner I/O.
  Compare the analytics response against the sealed grant pin and deployment;
  require this gate around source/page/proof/dataset owner reads.

Focused alert tests cover exact signed grant requests, source-owner assertions
without a user bearer, pin drift and revocation during owner I/O. Analytics
tests cover the sealed grant digest, deployment revocation during the alert
response, and denial before source disclosure. Logs:
`artifacts/g4-worker-grant-focused-20261107.log` and
`artifacts/g4-worker-grant-analytics-20261107.log`. The real Compose owner
smoke accepts a signed grant read through fresh source/dataset pins and rejects
the grant after revocation (`artifacts/g4-worker-grant-docker-20261107.log`).
Dataset row reads and scheduled execution remain under 5.17; the secret still
defaults empty.
- [x] 5.23 Add a separate scoped machine evidence-policy owner read. Renew the
  grant and policy across owner I/O; require the policy in sealed `emit_alert`,
  match its revision digest, and recheck deployment and grant after response.

Focused alert boundary/service tests reject changed policy targets/revisions
and policy revocation during grant renewal. The analytics HTTP test reads the
sealed grant and policy, rejects an unpinned policy, then revokes deployment
during policy response and rejects it. Logs:
`artifacts/g4-worker-policy-alert-20261107.log` and
`artifacts/g4-worker-policy-analytics-20261107.log`. The same Compose smoke
accepts a signed usable policy, then rejects its grant after revocation;
`artifacts/g4-worker-grant-docker-summary-20261107.json` records all three
machine checks. Dataset row reads, scheduled execution and effect-time alert
write remain under 5.17/5.16; the worker secret still defaults empty.
- [x] 5.24 Add scoped machine reads for an existing sealed native as-of
  manifest, event-time result and finite split. Require exact signed request
  bytes, actor-owned snapshot access, a declared lookup projection, current
  grant/dataset/deployment checks around owner I/O and manifest validation.

Focused device and analytics tests cover exact target/body/operation, snapshot
generation drift, projection mismatch, manifest mismatch and deployment
revocation during owner I/O (`artifacts/g4-worker-rows-device-20261107.log`,
`artifacts/g4-worker-rows-analytics-20261107.log`). The real Compose fixture
accepts signed manifest, point and three-segment split reads and rejects a
changed operation (`artifacts/g4-worker-asof-docker-summary-20261107.json`).
These private reads do not
seal a snapshot or schedule execution; durable prepared snapshot selection and
the worker restart/revoke/competition proof remain under 5.17.
- [x] 5.25 Add a bounded read-only ACTIVE deployment candidate scan and fresh
  machine admission across the sealed graph's source, dataset, grant and policy
  owners. Recheck the exact deployment after owner I/O and preserve the
  source's conservative `ruleExecutionReady=false` status; consume no timers.

Focused tests verify current owner reads and refusal when the deployment is
revoked during admission (`artifacts/g4-worker-admission-focused-20261107.log`).
Autonomous execution, qualified coverage and effect writes remain under 5.17.
- [x] 5.26 Restore an original journal cut through scoped machine capture,
  observation-page and coverage-proof reads. Reuse the existing cut digest,
  generation/checkpoint and before/after owner fences; reject changed cuts
  and revoked machine authority without a stored user bearer.

Focused recovery tests cover cut drift, grant revocation, machine pages and
proofs (`artifacts/g4-worker-recovery-focused-20261107.log`).
This adapter does not yet replace the bearer-based timer reconstruction path;
scheduled consumption and finalization remain under 5.17/5.16.
- [x] 5.27 Reconstruct a direct-journal diagnostic window from the durable
  recipe and ACTIVE sealed deployment through machine grant, policy and source
  owner reads. Reject wrong account/rule/epoch, changed deployment and
  unprepared lookup nodes before any timer work.

The existing staged-window H2 scenario now proves machine reconstruction of
the exact key/pins and idempotent stage replay without a user bearer;
focused guards reject scope and unprepared as-of reads
(`artifacts/g4-worker-window-restore-positive-20261107.log`,
`artifacts/g4-worker-window-restore-focused-20261107.log`). Due-timer
consumption is still manual pending a qualified scheduler in 5.17.
- [x] 5.28 Add an opt-in bounded scheduled drain for diagnostic WINDOW_CLOSE
  outputs. Discover ACTIVE deployment candidates, scope due-timer SQL by
  account/rule/epoch, rotate bounded deployment pages, renew machine owners
  and reconstruct each original cut before the keyed lease/CAS consume.
  Leave failed timers pending and emit
  only existing unfinalized MEASURE outputs, never alert effects.

The grouped analytics run covers scoped timer discovery on H2, worker owner
refusal and the diagnostic drain contract
(`artifacts/g4-worker-scheduler-focused-20261107.log`). The staged-window H2
path also verifies an expired original lease can be claimed by the machine
drain, emits one unfinalized measure, and repeats without a second result
(`artifacts/g4-worker-diagnostic-drain-h2-20261107.log`). The scheduler is off
by default (`app.vcl.worker.enabled=false`) until the remaining 5.17
qualification and production coverage gates are proven.
The grouped scheduler block passes 107 H2/adapter tests, including rotating
candidate pages, exact scoped timer discovery and machine reconstruction
(`artifacts/g4-worker-scheduler-block-h2-20261107.log`).
The same SQL contracts pass 68 PostgreSQL cases
(`artifacts/g4-worker-scheduler-postgres-20261107.log`); the full Compose app
stack returned HTTP 200 on ports 8080–8089, ML 8090 and frontend 3000 after
the worker wiring restart. The diagnostic scheduler remains disabled by
default; this is not autonomous RULE activation acceptance.
The H2 staged-window path also retains a due timer across a fresh worker
instance after grant revocation and after deployment revocation
(`artifacts/g4-worker-diagnostic-recovery-h2-20261107.log`). Competing workers
still require a real multi-process acceptance run before checking 5.17.
The same machine drain reads a supplied coverage proof, preserves UNKNOWN when
the proof is unavailable and writes `finalized=false` with no alert effect
(`artifacts/g4-worker-proof-diagnostic-h2-20261107.log`).

Checkpoint2026-11-04: V020 and the signed reservation/inspection API pass18 cases
across H2/PostgreSQL. They prove same-key replay, conflicting and identical races,
quota/expiry, Unicode keys, malformed/authority-amplifying input, foreign scope and
fresh owner revocation/failure checks even with legacy authorization disabled.
The full backend reactor passes1156 tests across16 modules, with no failures,
errors or skips. Structure, OpenSpec104/104 and VCL documentation checks pass.
Logs remain ignored in artifacts/g4-reservation-*. Reservation is not a grant,
rule definition, activation or alert/delivery effect. Continue4.2/4.3 and actual
normalized source readiness for G0 task1.4; broad runtime tasks remain unchecked.

Reviewed grants checkpoint2026-11-04: V021 and the private reservation/prepare/
confirm/inspection/reconcile/revoke protocol pass42 H2/PostgreSQL cases. They cover
exact/source-only scopes, actual signed HTTP dependency reads, consent drift,
lost acknowledgements, atomic SQL rollback, immutable receipts, concurrent apply/
revocation, account/source loss and fresh database expiry after lock waits.
The full backend checkpoint passes1203 tests across16 modules without failures,
errors or skips (artifacts/g4-grant-backend-tests.log). Structure, VCL documentation
and OpenSpec104/104 checks pass. These tests do not qualify normalized capture or
actual alert/effect admission;4.3 stays open. Compiler HTTP grant adapter work is
validated separately under G0, after this checkpoint.

Evidence-policy checkpoint2026-11-04: V022 and private-reference-evidence-1 pass40
H2/PostgreSQL contracts: exact consent/limits, real signed owner HTTP reads and
controller→shared transport/codec, immutable receipts, replay after grant expiry,
Unicode reservations, quotas, unknown/lost ACK, SQL rollback, concurrent apply,
revoke-before-effect/no-effect reconciliation and revoke during remote inspection.
Fresh database time after a lock wait prevents expired review dispatch. Seven
analytics adversarial HTTP cases bind policy pins to the separately authenticated
grant snapshot. The focused regression covers112 cases including existing grant/
RULE reservations. Full backend:1297 tests across16 modules, zero failures/errors/
skips; artifacts/g4-evidence-policy-backend-tests.log. Structure, OpenSpec104/104
and VCL documentation checks pass. Actual evidence storage/retention execution,
temporal activation and atomic alert/effect admission remain open; no archive.

Pure window-assignment checkpoint2026-11-04: VclWindowPlan passes46 focused cases,
including independent brute-force rolling membership across negative timestamps
and non-divisible steps, exact microseconds,64-window budget, all ISO week starts,
23/25-hour DST days, a31-day-plus-one-hour calendar month, half-hour transitions,
midnight gaps, skipped local dates, leap February, range limits and replay pins.
The full analytics reactor passes390 tests (310 analytics plus80 shared), with
no failures/errors/skips, including PostgreSQL bundle regression. Logs remain
ignored in artifacts/g4-window-*. This change follows the1323-test clean full
backend capture checkpoint; it does not claim a later1369-test all-module run.
Aggregation, coverage/finalization, durable timers/state/corrections and actual
alert/effect admission remain unimplemented by this kernel. Broad runtime tasks
and G0 owner/bundle composition remain open.

Interval-union checkpoint2026-11-04: vcl-interval-union-1 passes30 cases,
including the9h work union /8h after break /8.5h after corrected break vector,
an independent randomized occupancy oracle, clipping, adjacency/nesting, DST
days/months, exact pre-epoch microseconds, UTC-only literals, UNKNOWN/open/null
handling, malformed values, deterministic evidence, identity/budget/version/scope
fences and actual shared scalar compiler/evaluator predicate results. Full
analytics reactor:420 tests (340 analytics plus80 shared), no failures/errors/
skips; artifacts/g4-interval-union-analytics-tests.log. Structure, OpenSpec104/104,
VCL documentation and scoped whitespace checks pass. There is no new all-module
backend claim beyond the earlier1323-test clean checkpoint.

Task1.2 is reconciled with the already verified G0/1.3 and G0/2.1 scalar work:
all21 pure scalar kernels, checked IR, exact types/units/arity, Kleene/null/error
semantics and bounded compilation exist. This reactor reruns all43 ordinary
scalar,24 spatial and66 common compiler cases plus existing numeric AST tests.
This checkbox records shared requirement coverage, not newly implemented scalar
code or a qualified temporal runtime. Main1.3/1.4 and effect boundary4.3 remain
open; supplied-batch interval arithmetic does not establish complete evidence,
durable correction history, lower-bound emission eligibility or activation.

Window-reduction checkpoint2026-11-04: vcl-window-aggregate-1 passes38 focused
cases, including all five reducers, an independent randomized oracle, exact
pre-epoch membership, same-time distinct observations, cancellation/overflow,
final-only decimal rounding, units/absolute temperature, integral duration AVG,
Unicode code-point order, empty/null/unknown/evidence limits, scope/registry/plan
fences, actual shared compiler graphs and rolling/DST/calendar/correction vectors.
The full analytics reactor passes458 tests (378 analytics plus80 shared), zero
failures/errors/skips, including PostgreSQL bundle regression. Logs stay ignored
in artifacts/g4-aggregate-*. Structure, OpenSpec104/104 and VCL documentation checks
pass. No new all-module backend checkpoint is claimed. Batch arithmetic does not
close coverage/finalization, durable runtime or grant-fenced alert/effect gates.

Root-timing checkpoint2026-11-04: vcl-timing-1 passes19 focused cases, including
exact skew/idle boundaries, pre-epoch microseconds, source/reference minimum,
single lateness subtraction, unknown/missing/duplicate/changed progress,
overskew quarantine, replay/authority/document guards and maximum Long durations
without overflow. The full analytics reactor passes477 tests (397 analytics
plus80 shared), zero failures/errors/skips, including PostgreSQL regression.
Structure, OpenSpec104/104 and VCL documentation checks pass; ignored evidence
is artifacts/g4-timing-*. This pure time frontier supplies no qualified progress
owners, input-generation/state persistence, coverage or finalization authority.

Keyed-state checkpoint2026-11-04: V035 and vcl-keyed-state-1 pass24 H2/PostgreSQL
cases in the full1480-test backend run across16 modules, with zero failures,
errors or skips. Subsequent bounded-lock/query hardening passes26 focused cases,
including actual lock timeout with no state/inbox effects and safe same-intent
retry. Registration/CAS/lease races, full key tuple/hash checks, expired worker
after lock waits, lost commit ACK, SQL rollback, replay/correction conflicts,
account/key isolation, canonical state corruption and UTF-8/depth/inbox limits are
covered. Evidence stays ignored in artifacts/g4-keyed-state-*. Structure,
OpenSpec104/104, VCL docs and scoped whitespace checks pass. There is no new
all-module1482-test claim after the timeout hardening. Task5.6 and broad1.4 remain
open: timers/derived/rekey/outbox, actual owner/generation admission, global quotas,
coverage and real typed evaluator transitions are not implemented by state/inbox.

Atomic timer checkpoint2026-11-04: V036 and the namespaced input/schedule
transaction pass44 H2/PostgreSQL cases, including all state/inbox regressions,
restart/full-key discovery, exact due time and schedule revision, input UUID
namespace collision, correction invalidation, atomic consume/replace rollback,
concurrent timer consumption, lost commit ACK with no timer resurrection,
order-independent schedule digests, limits and corruption/lease rollback. The
full analytics reactor passes521 tests (441 analytics plus80 shared), zero
failures/errors/skips, including full PostgreSQL migrations. Structure,
OpenSpec104/104, VCL docs and scoped whitespace checks pass. Ignored evidence:
artifacts/g4-keyed-timers-*. The last all-module checkpoint remains1480 tests;
this is not a new1500-test backend run. Task5.6 remains open: no active scheduler,
derived/rekey/outbox effects or actual owner/generation/coverage/evaluator admission
is qualified by timer storage and exact due arithmetic.

Atomic output checkpoint2026-11-04: V037 passes70 combined H2/PostgreSQL
state/timer/outbox cases. New cases cover all output kinds, full rekey identity,
restart, exact reuse/conflict, lost input/ACK commit acknowledgements, immutable
owning receipt metadata, scoped/fenced ACK races, partial SQL batch rollback,
retained budgets and payload/revision corruption. A delayed output write crossing
the lease deadline rolls back state/timer/inbox/outbox before commit. The full
analytics reactor passes547 tests (467 analytics plus80 shared), no failures,
errors or skips. Structure, OpenSpec104/104, VCL docs and whitespace checks pass;
ignored evidence is artifacts/g4-keyed-outbox-*. The last all-module checkpoint
remains1480, not an unrun1526-test claim. Actual external receipt validation,
senders/derived/rekey consumers, owner/grant/generation/coverage/evaluator admission
and global quota/retention work remain open under5.6/1.4/4.3.

Atomic local rekey checkpoint2026-11-05: V038 and consumeRekey pass102 combined
H2/PostgreSQL state/timer/output/rekey cases, including16 new cases per database.
They cover source-qualified UUID namespaces, self/opposite-direction transfers,
identical delivery races, verified immutable receipts, lost ACK/expired-lease
replay, stale source/target fences, SQL rollback after target work and expiry
during receipt SQL. The full analytics reactor passes593 tests (513 analytics
plus80 shared), without failures/errors/skips. Structure, VCL documentation and
OpenSpec104/104 pass. Evidence stays ignored in artifacts/g4-rekey-*.
This primitive applies a supplied target transition and local source ACK only;
autonomous scheduling/evaluation, fresh owner/grant/generation/coverage admission,
derived output ownership and actual alert effects remain open under5.6/1.4/4.3.

Pure duration-witness checkpoint2026-11-05: vcl-duration-witness-1 passes22
focused cases, including200 independent randomized microtick-oracle batches.
Zero duration needs an actual eligible TRUE witness, including with equal-time
FALSE/UNKNOWN facts; positive duration requires actual endpoints and an explicit
bounded hold contract. Tests cover exact gaps/thresholds, false/unknown/null reset,
no extrapolation/count shortcut, boundary-only samples, preserved earlier witnesses,
pre-epoch microseconds/Long.MAX threshold, full partition identity, plan/scope/
kernel fences, malformed values and independent input/evidence budgets. The full
analytics reactor passes615 tests (535 analytics plus80 shared), without failures,
errors or skips. Structure, VCL documentation and OpenSpec104/104 pass; ignored
evidence is artifacts/g4-witness-*. This is supplied-batch arithmetic;5.6/1.4/4.3
and103.1 remain open for actual owner/coverage/session-occurrence admission,
durable evaluator/corrections and real effects. No new all-module backend claim.

Durable witness checkpoint2026-11-05: V039/DurableDurationWitness pass128 combined
H2/PostgreSQL state/timer/output/rekey/witness cases (13 new cases per database).
The full analytics reactor passes641 tests (561 analytics plus80 shared), no
failures/errors/skips. Tests verify typed measured transitions, append-only result
intents after corrections, original-result replay after later state changes,
actual lost SQL commit ACK, lookup/acquire race, SQL rollback,128-sample retention,
scope/window/rule fences, receipt/output corruption and real V035–V038→V039
schema upgrades containing preexisting inbox rows. Old null receipt digests remain
unchanged; exact-transition replay works, while the new workflow cannot invent
legacy input/result bindings. Structure, VCL docs and OpenSpec104/104 pass. Logs
stay ignored in artifacts/g4-durable-witness-*. This single-witness profile has no
autonomous graph worker, actual owner/grant/generation/coverage/session admission,
derived materialization or alert effects;5.6/1.4/4.3 and103.1 stay open.
