# Implementation and verification

Priority amendment 2026-11-11: [AI-first Java/LangGraph4j](../README.md#ai-first-execution-priority)
places library qualification/migration before the former unconditional A5 queue.
This change keeps workflow/skill/V/R and Spring guard ownership; graph traversal
and consultation scheduling use [one library runtime](../adopt-langgraph4j-agent-orchestration/proposal.md).
All unfinished quality/parity criteria and historical scores below remain intact.

## Native contract hardening — 2026-11-02

- [x] 9.1 Lower planner conditional outcomes to disjoint provider-supported variants and state-bound decisions; preserve canonical validation and old review readers.
- [x] 9.2 Constrain set_data to authorized widget/type/view-compatible choices and placement/target shapes; provide targeted diagnostics without guessing semantic replacements.
- [x] 9.3 Make the creation wire's global period explicit and separate from per-widget period; retain the canonical operation protocol and confirmation boundary.
- [x] 9.4 Tighten routing/domain prompt responsibilities and parameter visibility semantics, retain source-linked requests, and preserve failed-call usage/technical diagnostics.
- [x] 9.5 Verify general contract regressions, SDK fixtures and owning compiler outcomes offline, update current docs, and start the Docker service with health checks. Do not rerun real-model campaigns or alter frozen scenarios/graders for this implementation.

Offline evidence: final focused reactor passed 378 tests (272 AI, 95 dashboard,
11 common), zero failures/errors/skips. SDK HTTP fixtures retain the decision
constraints in actual native tool requests. Tests cover canonical/wire outcome
separation, type/view choice compatibility including ordered view changes,
immutable context, global/chart period separation and correction round trips,
model-facing examples, target/anchor forms and failed-call usage. Prompt limits
remain unchanged. OpenSpec 87 items, structure and whitespace checks passed.
AI/dashboard Docker services were updated and all 12 HTTP endpoints are UP.
Saved-context offline reconstruction in NATIVE_TOOLS uses 103851 bytes / 107947
estimated input tokens and fits the unchanged 112000 input limit.
No real-model calls, training or frozen corpus/grader edits in this fix. Passing
offline engineering checks do not close semantic quality, A5/A6 or task8.2.
Evidence: ignored artifacts/native-contract-{fixes-report.md,test-reports.tar,
tests-final-accepted.log,health.json,saved-context.json}.

## Authorized native-tool live diagnosis — 2026-11-02

The user explicitly authorized real-model checks on the current Docker stand
using the supplied demo account, with diagnosis of code/prompt causes and Spring
AI replacement opportunities. Keep the 27 frozen cases and their graders
unchanged, one trial each, STANDARD and the selected gpt-5-nano NATIVE_TOOLS
configuration, embeddings OFF. Limit the campaign to 160 model calls, 4500
seconds and 16000000 reserved cost micros (reservation is not billed cost).
At most two independent cases run concurrently; record that latency comparisons
with sequential historical runs are confounded. Fault injection without a
disposable provider remains blocked. A separate owned fixture may exercise
confirmed creation and same-ID draft updating, with cleanup; it is not part of
the frozen planning score. No prompt/runtime tuning or training in this campaign.

- [x] 8.1 Run the frozen native-tool campaign, retain first-pass/recovery accounting and safe diagnostics, and restore active selection/settings.
- [ ] 8.2 Verify an isolated confirmed creation/update lifecycle and exact reviewed/saved equality; keep semantic failures and execution/harness limits explicit.
- [x] 8.3 Compare historical runs and map observed failures to code/prompt boundaries, distinguish hypotheses from evidence, and prioritize Spring AI replacements without weakening domain guards.

Recorded outcome: 35/54 turns, 13/27 scenarios; 3 fault-provider turns blocked,
16 executed turns failed. 106 frozen-campaign model calls, including 15
correction/recovery calls; provider usage available for all 106 exchanges.
19 model attempts failed canonical/graph validation; these are not 19 independent
failed turns. One offline production-class probe confirms that conditional
canonical planner constraints are absent from the lowered wire schema.
Historical full runs: 39/54, OFF 36/54 and ON 37/54. Corpus/design/grader hashes
match; changed mode/context/concurrency and one trial prevent causal claims.

Task 8.2 remains open: both separately reported owned lifecycle trials stopped
before confirmation because global period remained LAST_24_HOURS rather than
the requested LAST_1_HOUR. The first writer set only the history widget period,
not global set_period. No unsuitable review was executed and saved equality /
same-ID updating were not reached. A supplementary fixture criterion was
corrected to permit movement reflow; it did not alter frozen criteria or rescue
the failed period checks. Total with these trials: 110 model calls, input
1013213/output 336013 tokens; no price snapshot, so billed monetary cost unknown.
All fixtures cleaned, dashboard inventory unchanged, original selection and
model settings restored/unchanged, lease released, 12 HTTP endpoints UP.
Production sources and grader remained unchanged during the campaign.
Evidence: ignored artifacts/native-live-20261102/{report-ru.md,findings.md,
analysis.json,comparison.md,postflight.json,overall-accounting.json}.
OpenSpec 87 items and structure checks passed; broad A5/A6 quality gates stay open.

## Dashboard native tools — 2026-10-30

- [x] 7.1 Expose separate Spring AI native proposal tools for creation, unexecuted review refinement and existing draft updates, selected only by the registered typed intent.
- [x] 7.2 Preserve focused skill prompts, registered operation schemas, bounded single exchanges and immutable review/confirmation execution; reject foreign or multiple tool calls without fallback.
- [x] 7.3 Verify real Spring AI SDK exchanges against local provider fixtures and canonical operation round trips, including movement and settings; document explicit model capability activation and limits. No real-model campaign or training.

Offline verification: 271 Java tests passed (260 AI and 11 common). Actual SDK
HTTP fixtures cover OpenAI strict native tools, Anthropic and Gemini forced tool
selection, exactly one exchange, wrong/multiple call rejection and retained usage.
Canonical dashboard examples and placement/size/settings contracts pass;
creation, refinement, update, planner and assessor boundaries remain distinct.
Evidence: ignored artifacts/dashboard-native-tools-final-tests.log and
dashboard-native-tools-test-reports.tar. This does not close real-model quality gates.
The Docker AI service was restarted. The existing authorized demo gpt-5-nano
configuration was explicitly activated through its ordinary scoped API with
supportsTools=true and responseFormat=tool; all other model settings were
verified unchanged. Activation made zero model calls. Evidence:
artifacts/dashboard-tools-activation.json and dashboard-tools-model-{before,after}.json.

## Context compression — 2026-10-30

- [x] 6.1 Add a lossless wire catalogue index with shared exact parameter contracts/schemas and retrieval references; preserve every capability on search misses.
- [x] 6.2 Remove redundant edit-context copies through explicit references while retaining exact dialogue, goals, pending questions, accepted definitions and bindings.
- [x] 6.3 Reproduce the selected-vehicle context-size failure from saved authorized stand data and verify admission after compaction without raising routing or user-input limits.
- [x] 6.4 Verify offline round-trip contracts, writer/assessor isolation, source immutability and request-size reduction; document the projection and limits. No new real-model campaign or training is part of this fix.

Offline reconstruction of the failed turn's saved domain context, accepted review
and dialogue shrank from 108075 to 87011 bytes; two workflow loads from 171867 to
106075 bytes. Both now fit unchanged admission limits. This reconstruction is
not a byte-identical original wire replay or semantic quality result. Exact
catalogue/schema round trips, different defaults/bundles, per-load data choices,
legacy fallback and projection isolation pass in the 271-test suite above.
Evidence: ignored artifacts/context-compression-*.json and
context-compression-final-tests.log (earlier compression-only 265-test suite).

## Offline reliability follow-up — 2026-10-29

The user authorized general engineering fixes and explicitly prohibited real-model
tests. The frozen corpus/grader and historical scores remain unchanged. This
package does not close A5 semantic-quality, V/R or A6 qualification gates.

- [x] 3.1 Add an explicit source/accepted-review edit context and isolated branch subject projection; keep assessor inputs independent from writer goals.
- [x] 3.2 Normalize only unreferenced technical ID collisions and provide outcome-specific correction diagnostics; retain strict ambiguous-reference and unknown-field rejection.
- [x] 3.3 Renew client sessions before bounded planning and reject insufficient server-side token lifetime before provider attempts; preserve single-flight and account-switch guards.
- [x] 3.4 Correct offline branch/account-bootstrap/retained-preview readers and retain redaction provenance across pre-redacted submissions.
- [x] 3.5 Adapt deterministic assistant-v2 history/trace/fault fixtures without credentials from real providers or model calls.
- [x] 3.6 Verify backend/frontend/harness locally, update contracts/operations and preserve failed quality evidence.

Offline verification: 248 Java tests (237 AI service), 414 frontend tests and 57
Python harness tests passed; frontend typecheck/build and repository checks passed.
Docker history and trace smoke passed against an isolated deterministic HTTP
fixture. Trace records both provider usage events for two separate attempts even
when usage payloads match. Evidence is in ignored artifacts/ai-offline-fixes-*.
No real-model calls, training or frozen-corpus/grader edits were performed.
Natural-language quality acceptance and the broader tasks below remain open.

## Widget catalogue retrieval — 2026-10-29

- [x] 4.1 Preserve owner descriptions, parameter constraints, defaults and compatibility in the AI catalogue.
- [x] 4.2 Build bounded hybrid capability retrieval with immutable catalogue identity and optional Spring AI embeddings; retain the complete authoritative catalogue.
- [x] 4.3 Wire retrieval into dashboard writer context, with explicit configuration, safe failure and no real-model calls during verification.
- [x] 4.4 Verify catalogue/schema consistency, ranking, index invalidation, invalid vectors, unavailable provider and writer/assessor separation; update operating guidance.

Verification: 351 Java tests passed (245 AI, 95 dashboard, 11 common), including
the Spring AI embedding SDK against a local HTTP fixture with retry rejection.
OpenSpec (87 items), structure, Compose configuration and whitespace checks passed.
Evidence: ignored artifacts/widget-catalog-tests-final.log and
widget-catalog-test-reports.tar. Embeddings remain disabled by default; actual
index generation and multilingual retrieval quality have not been qualified.
No real-model calls, training or frozen evaluation changes were made.

## Earlier implementation evidence

## Authorized Docker and embedding comparison — 2026-10-29

The user explicitly resumed real-model testing for Docker verification and a
matched embeddings OFF/ON comparison, including earlier campaigns. Use the same
27 frozen cases, one trial per case per arm, STANDARD profile and pinned existing
chat configuration. Keep scenario predicates and earlier reports immutable.
Each arm is capped at 160 chat calls, 16,000,000 reserved cost micros and 4500
seconds; the reservation ceiling is not a statement of actual billed cost.
Fault cases remain explicitly blocked unless a disposable keyed provider exists;
never reroute the shared demo provider. Local embeddings use pinned multilingual
MiniLM weights in a separate bounded CPU container. No training is authorized.

- [x] 5.1 Start the Docker application and verify health, authenticated catalogue and existing deterministic history/trace checks.
- [x] 5.2 Run the frozen corpus with embeddings disabled and collect safe outcomes, call accounting and failure evidence.
- [x] 5.3 Run the same corpus with real local embeddings and verify HYBRID evidence rather than silently counting lexical fallback as enabled.
- [x] 5.4 Compare matched OFF/ON cases and earlier campaigns, state model/profile/driver differences, preserve failures and restore user selection/runtime defaults.

Completed real-model evidence: OFF 36/54 turns and 13/27 scenarios; ON 37/54 and
15/27. Both retain three blocked fault-provider turns. Prior full Spring AI result
remains 39/54 and 16/27. ON has 44/44 HYBRID events and 45 real embedding requests;
OFF has no embedding requests. Chat calls: 102 OFF, 101 ON; each arm remains below
the tightened ceiling. The aborted infrastructure attempt adds nine calls outside
quality scores. One repeat per case, fixed order and cache effects do not establish
causal improvement. Ten earlier campaigns are compared with explicit corpus,
grader, profile, model-setting and driver caveats. No quality gate is closed.
Postflight confirms 12 healthy HTTP endpoints, unchanged chat settings, restored
active selection, released account lease and embeddings OFF. Evidence and detailed
failed outcomes: artifacts/widget-ab-20261029-r3/report-ru.md and comparison.json.

Startup found a general catalogue-size regression before dashboard generation:
the complete owner settings schema exceeded the user-input item budget. The first
OFF attempt is retained as an aborted infrastructure run, not a quality baseline.
Use a separately bounded/secret-scanned fetched-catalogue path; client input limits
remain unchanged. Add regression tests before repeating both arms on identical
fixed code. The follow-up tightens both arms to 140 calls, 14,000,000 reserved cost
micros and 4200 seconds, leaving room within the original cap for aborted calls.

Guard correction: 354 Java tests passed (248 AI, 95 dashboard, 11 common), including
full structured catalogue enrichment and unchanged client-input rejection. The
tested guard class hash matches the runtime class. Actual frontend inventory,
published dashboard cards and lazy-loaded charts were inspected. All 12 HTTP
health/UI entrypoints passed and the previously stopped HSL connector was resumed
healthy. History and trace fixture checks remain separate from real-model quality.
Initial regression evidence is retained under artifacts/widget-ab-20261029;
an interrupted job's stale account lease was verified and cleared before the
matched follow-up under artifacts/widget-ab-20261029-r3. No quality scores from
aborted setup runs are substituted for the new comparison.

2026-10-27: explicit user authorization resumed implementation and local real-model
checks, including a local primary-generation override. Production wiring and all
13 tool contracts exist, with shared review/progress UI, scoped context snapshots,
PINNED single-budget transport and partial structural verification/repair. This
does not complete the acceptance tasks below: HSL step separation, persisted
dashboard draft updates, exhaustive parity and final V/R quality remain open.
Failed live reports are preserved in separate ignored artifact directories;
no dashboard execution is part of these planning trials.

Reuse audit after the user's clarification: dashboard operations, composition
preparation, immutable storage, confirmation/execution and shared preview use the
existing implementations. The duplicate v2 undo/copy loop was removed in favour
of `DashboardReviewHistory.restore`; its existing test now also covers scoped v2
ancestry and retained SUPERSEDED reviews without changing legacy keep eligibility.
Latest completed live checkpoint (`live-12`, finished 2026-10-28 local time)
passed 1/5 turns and neither full scenario, so acceptance remains failed.
The original frozen criteria were retained. The first creation was rejected by
domain preparation; its continuations did not recover. The second creation
passed, but its READY move failed top-placement and untouched-order assertions.
Structural preservation against model-selected masks does not establish that
the selected targets match the user's intent. See the reuse matrix and current
verification limitations in the API document. No further live series was run.

Subsequent review-edit work retains the accepted review/output bindings during
PREPARING and failed candidates, atomically supersedes a replaced READY review
with the new state commit, and exposes acceptedReview as a read-only retained
preview after reload. Pending/unknown requests do not expose an executable plan.
Typed controls and saved-review execution no longer resolve a model. Planner graph
and declared question-field validation share the existing one-correction A2
boundary; invalid JSON and registered schemas carry bounded safe diagnostics.
Targeted offline checks cover these paths plus existing operation/executor
boundaries. These increments do not close the full parity/V/R acceptance tasks.

- [ ] 1.1 Map every currently supported tool/workflow in an exhaustive parity matrix; permit explicit temporary legacy compatibility, but require v2 skills for all supported NL scenarios before full cutover.
- [ ] 1.2 Migrate real device creation for all catalogue types, locations and optional parameters, with model proposals and deterministic contract validation.
- [ ] 1.3 Expose reviewed source connection only for implemented integrations; separate new step receipts/confirmation from immutable legacy composite HSL reviews.
- [ ] 1.4 Migrate dependent created-device dashboard continuation, reload, two devices and explicit model-selected resource changes using actual source/field capabilities.
- [ ] 1.5 Migrate dashboard creation, move/resize/settings/remove/history operations using existing semantic compiler, preservation masks and exact stored undo; distinguish unexecuted review refinement from reviewed UPDATE_DASHBOARD_DRAFT on the same actual dashboardId with optimistic revision checks.
- [ ] 1.6 Integrate verify-ai-dashboard-edit-outcomes before READY and repair-ai-dashboard-planning-failures through the shared routing/budget service.
- [ ] 1.7 Remove v2 semantic regex/keyword/question-text paths and nested provider retries; preserve secret redaction and technical format validators.
- [ ] 1.8 Integrate frontend plan progress, questions, explicit review confirmations, dependency/partial failure, cancellation and truthful retry; retain shared preview.
- [ ] 1.9 Test device/source/simulator/rule/read-only parity, no-data/stale, migration/reload/rollback, two-account isolation and reviewed-created equality offline.
- [ ] 1.10 Document compatible APIs/storage/operations and prepare isolated capped live qualification inputs without enabling defaults or running unrequested trials.

## 2. Engineering stabilization before remaining workflow expansion

Amendment 2026-10-28: these are A5 work packages, not new program stages.
Execute 2.1 -> 2.2, complete core workflow work in 1.1–1.5/1.7, then 2.3.
Only afterward integrate V/R in 1.6, finish recovery UI/acceptance in 1.8–1.10
and close A3 task1.9 when its complete evidence exists. No task is waived.
Existing checked A1/A2/A4 milestones and failed live scores remain unchanged.

- [x] 2.1 Diagnose the two existing dashboard families by safe stage/code; add repository-owned sanitized failure fixtures and compact versioned skill sections/examples plus a grounded current-widget inventory without dropping mandatory dialogue or semantic regex routing.
- [x] 2.2 Implement capability-selected native strict JSON/tool output with canonical schema projection/normalization and declared JSON_TEXT compatibility; test malformed output, unsupported schema, refusal/truncation and no hidden model/mode downgrade under the single A2 budget.
- [ ] 2.3 Separate source-grounded requested effects from operation generation; provide an explicit diagnostic first-pass profile with automatic repairs/model judges disabled, predeclare first-pass targets and call/token ceilings, and score the original two families plus independent paraphrases without retry promotion; retain basic schema/access/confirmation guards and record every necessary planner/step/context call. Offline fixtures do not count as model-quality acceptance; live evidence requires explicit authorization. Add V/R only after this checkpoint.
- [x] 2.4 Separate version-2 initial dashboard creation, unexecuted review refinement and persisted draft update proposals; restrict planner inputs to resource bindings, remove creation widgetKey fields, validate dynamic keys/local references before owning preparation and retain accepted review focus across graph replacement. Verify contract examples/lowering, mandatory context and legacy reader/execution regressions offline; this does not close workflow or live quality acceptance. The server routing bypass found in campaign03 is fixed and an actual create-to-refine dialogue passed in campaign04; model-selected wrong routing is still rejected and remains part of the failed quality gate.

- [x] 2.5 Implement general data-driven dashboard design with exact user constraints and delegated discretion, plus an explicit source-grounded compiled-result assessment profile through A2 before save; verify no-save on rejection/uncertainty/invalid evidence and preserve diagnostic judge-free execution. Calibration, full UI and quality acceptance remain in existing unfinished tasks.

2026-10-28 core stabilization: 29 focused offline checks passed across
PrimaryStrictOutputTest, CurrentWidgetInventoryTest, FirstPassDiagnosticTest,
SkillContextScenarioTest, DashboardOperationCompilerTest and
AssistantRoutingBudgetScenarioTest. Sanitized frozen failures are in
`evaluation/a5-dashboard-failures-v1.json`; stage/root-cause uncertainty is explicit.
See the current API's A5 stabilization contract. Package instructions v2 retain
command v1 compatibility. Strict projection/native tools are offline-tested;
real-provider compatibility and improved model quality are not yet established.
Task 2.3 remains unchecked: its explicit no-correction/no-judge profile and numeric
checkpoint targets/ceilings are prepared, but core workflows and independent
live first-pass evidence remain unfinished. The user subsequently expanded
authorization to the full A5 -> A6 queue and supplied a demo account; no live run
has yet been made in this increment. Live-12 remains 1/5 turns and 0/2 dialogues.

Follow-up in the expanded scope, 2026-10-28: separate HSL source execution uses
existing connector receipts/stable keys; UPDATE_DASHBOARD_DRAFT reuses existing
composition and domain draft saves with etag/baseRevision and atomic idempotent
receipts. Shared preview/confirmation recognizes updates. These are partial
1.2-1.5/1.8 increments, not full parity acceptance; arbitrary externally-edited
draft import and update undo/history remain open. Legacy composite reviews remain
unchanged. Planner catalogue exposes schema-owned bounds/enums; resolved initial
names now reach dashboard context. HTTP preparation failures have safe categories
and codes; access/resource/dependency failures are not automatically repaired.
A format correction is not nested inside a domain repair call.

Evidence: 31 focused core checks, then 7 catalogue/protocol/context checks passed;
17 AI and 11 dashboard PostgreSQL/focused checks passed using an explicit Docker
endpoint. An earlier full AI run passed188 checks; a later full attempt had2 Docker
environment errors (179 remaining checks passed), followed by the successful
focused integration recheck. Frontend typecheck/build and all412 tests passed
with --maxWorkers=2; the unrestricted run had one unrelated ML-page timeout.
Harness44 tests passed; structure and strict OpenSpec86/86 passed.

Live diagnostic evidence is deliberately failed. Checkpoint01:0/9 turns, first
candidate rejected by preparation, then the harness stopped because it treated
an unrelated bounded CONTEXT trace as missing MODEL_CALL accounting. Original
report retained, never rescored. The missing initial name was independently
confirmed in the stored workflow context and fixed; invalid planner periods are
now constrained and advertised. Checkpoint02 is a linked, statistically
non-independent source-version reevaluation under the SAME remaining aggregate
ceiling:0/9, with unknown/forward widget reference and planner contract rejection;
remaining calls stopped at the campaign time ceiling. No model judge/correction,
model switch, provider edit or dashboard mutation occurred. The original live-12
remains1/5 turns and0/2 dialogues. Neither diagnostic run is A6 qualification.

A2 durable ledger evidence across the two diagnostic runs:5 calls,46,422 input
(including1,664 cached),22,111 output (including19,840 reasoning) tokens; zero
automatic corrections. Charged/reserved upper bound USD0.50; actual monetary
cost UNKNOWN because no price snapshot exists. The only configured provider/model
is OPENAI/gpt-5-nano (JSON_TEXT compatibility profile). No stronger reference or
second provider is available, so economic/portability qualification is incomplete.
Task2.3, A3 task1.9, remaining A5/V/R/C3/preview and A6 stay unchecked. Local
STANDARD execution profile is restored; existing local v2 override and deployment
defaults/provider settings are preserved. No change is archived.

Next: finish core1.1-1.5/1.7 and source-grounded first-pass2.3, specifically
new-widget references and planner continuation contracts, before final V/R and A6.
Do not restart the exhausted diagnostic campaign or promote reevaluations to
independent trials; any later series requires its own predeclared evidence budget.

Final targeted regression after the context/enum/legacy-reader adjustments:16
backend checks passed; authenticated profile inspection confirms STANDARD and
unchanged model settings. Harness44 includes shared campaign/time ceiling stops
before any further provider I/O. No archive/default enable was performed.

Responsibility follow-up, 2026-10-28: CREATE_DASHBOARD now creates only a new
composition without model widgetKey fields; REFINE_DASHBOARD_REVIEW selects an
unexecuted create review; UPDATE_DASHBOARD_DRAFT retains the existing same-id
versioned update path. All three use command/skill/output version2 and the same
existing internal operation/compiler/review/execution boundaries. Initial
creation requires exactly one name operation, ignores ambient dashboard review
focus and permits device receipt inputs. Edit keys use snapshot membership,
never identifier shape or unknown-key-to-creation fallback. Planner inputKeys
are resource-only; actual dependencies/goalIds are now included in planSummary.
Step replacement retains accepted review focus while invalidating the executable
binding. Dashboard prompts advertise only the three relevant loaders and carry
one selected dynamic command schema without dropping mandatory dialogue/data.

Verification:188 offline AI-service checks passed (Docker-backed tests excluded),
including new contract/lowering/reference/graph/context regressions. An initial
run exposed two new boundary-test failures; they were corrected and the full
offline suite passed. No live scores were recalculated, no paid provider calls
were issued, and provider settings/defaults were not changed. Task2.3 and full
1.1-1.10 acceptance remain open. Next: remaining core workflow parity and
source-grounded effect contracts before any fresh predeclared first-pass series;
V/R and A6 follow their existing gates.
After retaining widgetKeys solely as an edit-skill clarification field,12 focused
registry/schema/context checks passed. Planner widgetKeys remain forbidden.

Authorized verification after the responsibility split (2026-10-28, campaign03)
failed:1/9 turns and0/4 full dialogues, no dashboard mutation and successful
cleanup of all four owned fixtures. The first request produced a version2 review
using PLANNER and STEP, and passed its permissive first-turn predicates. A
supplemental independent role audit found a missing current:doors card (5 widgets
for6 requested roles); this does not rescore the immutable original report.
The rename turn then failed BEFORE any model call: IntentOrchestration.submit
directly prepares a sole WAITING_CONFIRMATION step using its old CREATE_DASHBOARD
intent, bypassing the planner/new refinement skill. The selected-base guard
rejected it with DASHBOARD_BASE_REQUIRES_EDIT_SKILL; accepted preview survived.
The harness subsequently halted7 turns as DIAGNOSTIC_ACCOUNTING_INCOMPLETE for
the zero-call failure. All stopped turns remain failed in the denominator.

A2 evidence:2 calls,15,812 actual input tokens (0 cached),6,697 actual output
(including6,016 reasoning),69,417ms combined model latency, no correction/recovery
calls. Charged/reserved upper bound USD0.20; actual cost UNKNOWN_UNPRICED.
Fresh budget was predeclared (40 calls,20 minutes,USD4 reserved ceiling), with no
restart of prior campaigns. This development reevaluation is not independent A6
qualification. Source/runtime manifests, immutable checkpoint report, role audit
and exact-turn ledger evidence are in artifacts/a5-verification-20261028-03/.
STANDARD was restored; provider/model settings and deployment defaults unchanged.
Historical live-12 remains1/5 turns,0/2 dialogues. Task2.4 is reopened for the
missing runtime transition;2.3 and other acceptance gates remain open. Next fix:
route new follow-up edits through planner, keep direct continuation for actual
pending-question answers, cover create -> refine end to end, and distinguish
known zero-call technical rejection from absent accounting in the harness.

A5 agreed design implementation (2026-10-28): follow-ups now route via planner except exact pending-question answers; authorization/receipt checks still precede provider I/O. Dashboard design instructions use general number/string/boolean/format principles, explicit-user constraints and delegated layout discretion without templates or preselected bundles. Optional VERIFIED adds source-grounded compiled-result assessment through A2 before save; no assessment format retry or model switch. FIRST_PASS_DIAGNOSTIC/production defaults are unchanged. Harness fixes distinguish evidenced zero-call failure and preserve failed-call accounting.

Offline evidence:190 AI-service checks passed (Docker-backed classes excluded), followed by7 focused final checks;49 harness checks passed. The new source-grounded assessment response/VERIFY phase, strict projection, no-save on reject/uncertainty/bad JSON/forged source, shared two-call test budget and create-to-refine transitions are covered. Live campaign04 is separately predeclared; final quality/visual/calibration evidence is not implied by these checks.

Campaign04 (explicitly authorized,2026-10-28) completed with failed quality: FIRST_PASS_DIAGNOSTIC3/11 turns,1/6 dialogues (frozen four families3/9,1/4); linked VERIFIED0/5 turns,0/3 dialogues. The same pinned model/output mode was retained. The complete heldout compact dialogue exercised CREATE_DASHBOARD -> REFINE_DASHBOARD_REVIEW successfully. Another rename was routed by planner but the model chose CONTINUE on old creation and the base guard rejected it; it is no longer the old server bypass. Missing requested roles and preservation/settings errors remain.

A new design-case oracle incorrectly checked dashboard default period instead of requested history period. Both exact/mixed candidates actually used a6-hour history, and one was accepted by source-grounded assessment. Original failed reports were retained, never rescored. Separate dashboard-design-ru-v2/historyPeriods predicates and wrong/missing-period negatives were added AFTER the campaign;50 harness tests passed and no paid v2 rerun occurred. Original corpus and design-v1 files remain unchanged.

VERIFIED made two actual assessment calls: one accepted candidate, one invalid source-evidence rejection. Its free-design planner failed even after one format correction. Two final turns stopped before I/O because remaining campaign time could not reserve a full240-second turn. All remain in the denominator; no outcome is promoted to first-pass success. Calibration remains incomplete.

Scoped A2 ledger:28 calls,246,885 input (24,960 cached),96,996 output (87,360 reasoning included),810,467ms model latency;20 first-pass calls and8 linked VERIFIED calls. Charged/reserved upper bound USD2.80, actual cost UNKNOWN_UNPRICED. Shared ceiling40 calls/20 minutes/USD4 was preserved. No domain recovery occurred; the only format correction failed. Both cohorts are development reevaluations, not independent A6 qualification. No dashboard mutation occurred and all owned fixture cleanup completed. STANDARD was restored and model/provider settings verified unchanged.

Desktop and390px mobile shared previews of the exact request rendered both widgets without observed panel horizontal overflow; technical speedKmh labels remain a design issue. This is partial browser evidence, not A3/V/R/UI acceptance. Source/runtime manifests, immutable reports, ledger and screenshots: artifacts/a5-design-verification-20261028-04/. Server source stayed constant through both cohorts. Tasks2.4/2.5 close engineering slices only;2.3 and full A5/V/R/A3/A6 quality gates stay open. No archive/default enable occurred.


Context deduplication follow-up,2026-10-28: primary-prompt-v1 removes loader result
schemas/duplicate trust metadata from model input, sends one response schema per
configured output mode, retains accepted definitions once with a compact widget
index and excludes historical generator parameters. Identical views arrays use
lossless per-workflow references. Canonical validation/storage, mandatory sources,
fresh draft version, all choices and existing budgets are preserved. Policy and
skill digests pin the new projection (dashboard skill guidance v5).

Evidence:195 AI-service offline tests passed, excluding the three Docker-backed
classes; exact definition/question/facts preservation, catalogue expansion and
all output modes are covered. One same-request offline creation reconstruction
shrunk from59,280 to48,351 UTF-8 wire bytes (-18.4%). This is not a paid replay or
a quality measurement. Existing first-pass failures,2.3 and remaining workflow/
V/R/UI/A6 checkboxes remain unchanged. Next engineering work remains planner
format/action reliability and precise skill completeness/edits, plus the requested
timeout/progress improvements; quality must be established by a separately capped
live series, never by recomputing prior failures.


Authorized live campaign05 (2026-10-28) used STANDARD: automatic corrections ON,
model judge OFF, at the user's clarification before any provider calls. Harness
v4 explicitly validates this profile/flags, rejects hidden assessment and retains
the existing shared A2/campaign ceilings.52 offline harness checks passed.

Real-model checkpoint: first-pass4/11 steps (36.4%); corrections recovered3/7
initial unsuccessful steps (42.9%); final7/11 (63.6%),4/6 full dialogues. The
all-predicates/all-dialogues checkpoint FAILED. Original two families remain1/5
steps and0/2 dialogues in this new sample too. Frozen four: first-pass2/9,
final5/9,2/4 dialogues. Every failed step stays counted; prior reports remain
immutable. This single development reevaluation is not independent A6 release
qualification and profiles/oracle versions are not directly comparable.

The initial tram candidate duplicated an operation id, then corrected successfully;
a separate no-rescore audit found all six requested roles in the accepted result.
Rename planner unnecessarily asked tramName and did not dispatch the edit skill.
Its subsequent move produced INVALID_JSON twice. Three-card creation failed
planner identifier format twice; dependent move had no accepted base and was
correctly rejected. Compact create/edit both needed format correction and passed.
Map/footer and delegated/exact design cases passed without correction.

A2 durable ledger:24 calls (19 primary,5 format corrections),187,005 input tokens
(24,320 cached),84,155 output (75,200 reasoning included),784,306ms model latency;
806s campaign wall time. All usage is present,0 judge calls and0 domain/outcome
recovery calls. USD2.40 charged/reserved bound is not actual billed cost; actual
cost remains UNKNOWN_UNPRICED.40-call/20-minute/USD4 reserved caps were retained.
No dashboard mutation occurred and all six owned fixture cleanups passed.

Mounted compiled classes were checked before the run; service source stayed
unchanged throughout; STANDARD and model/provider settings were retained. Real
provider calls used control telemetry ingested through ordinary stack APIs, not
physical-vehicle or browser acceptance. Evidence:
artifacts/a5-recovery-verification-20261028-05/report.md, analysis.json,
standard/summary.json and usage-ledger-evidence.json. A5 task2.3 and remaining
V/R/A3/UI/A6 gates remain open; no archive/default enable or old-score rescore.
Next: planner identity/output and unnecessary-question routing, then step format
reliability. Context reduction alone has not established sufficient quality.


Planner correction follow-up,2026-10-28: planner-references-v1 derives scoped
resource_refs bindings from the canonical planner schema and lowers them to exact
server identities before canonical/registry/graph validation. New goal sources
are assigned from the current message, retained goal sources remain unchanged.
All three configured output modes share this projection and policy fingerprint;
no mode/model/default change or extra budget is introduced. Invalid references,
resource-kind mismatch, literal UUID bindings and forged goal-source fields are
rejected. fact_ref and dependent step_output remain supported.

planner-routing-v4 and actual accepted dashboard name/type/status/requiredIntent
clarify the edited subject. Planner questions are intent/target routing questions;
domain fields such as tramName are rejected inside the existing correction seam.
CONTINUE on a prepared WAITING_CONFIRMATION dashboard is rejected before another
STEP and requires model-selected AMEND. No semantic keyword rerouting is added.
The accepted variant, confirmation and existing execution/compiler paths remain.

Evidence:201 offline AI-service tests passed, excluding the three Docker-backed
classes; OpenSpec86/86, structure and whitespace checks passed. New tests cover
three-mode transport/decode/lowering, exact scalar/multiple device identities,
unknown/mismatched references, forged sources, domain questions, nonempty goals,
complete guide examples, preserved fact/output bindings, accepted dashboard
subject and shared A2 usage retention/canonical replay without provider calls.
No real-model calls or new live scores were produced in this increment. Campaign05
and historical live-12 failures remain unchanged;2.3 and other acceptance gates
stay open. Next: a separately capped focused live planner regression using STANDARD
with corrections enabled and no model judge, then outstanding STEP format work.


## A5 planner campaign06 and offline context follow-up

Two authorized STANDARD cohorts used the same frozen original two dialogues and
shared40-call/20-minute/USD4 reserved caps (corrections ON, model judge OFF).
Before (planner references v1/routing v4): first-pass2/5, recovered1/3 initial
failures, final3/5,1/2 full dialogues. After schema-derived operation descriptions
and routing v5: first-pass2/5, recovered0/3, final2/5,0/2 dialogues. Both quality
gates FAILED. No prior score or criterion was changed; these known-case linked
samples are not independent A6 qualification.

The first run had no UUID-format errors and corrected one wrong CONTINUE through
A2. Rename was falsely UNSUPPORTED; the later edit retained the wrong name. The
catalogue fix did not demonstrate improvement: rename asked unnecessary intent/
target/name clarification, a current doors card was omitted, and another planner
bound a review as a device twice. A separate no-rescore audit found all six
requested roles before and a missing current doors card after, despite that
permissive initial turn's formal PASS. READY alone is not completeness evidence.

A2 ledger:19 calls (17 primary,2 format corrections),140,846 input (22,400 cached),
64,481 output (58,624 reasoning included),508,514ms model latency;826.5s live
campaign wall time including the intervening fix/build/startup. All ledger usage
was present. USD1.90 is a charged/reserved bound, not billing; actual cost remains
UNKNOWN_UNPRICED. All four owned fixture cleanups passed; no dashboard mutation,
provider setting/default change or judge calls occurred. Both source versions
were fixed within their live cohort; historical live-12 and campaign05 remain.

Further source-only planner-references-v2/routing-v6 makes input reference schemas
field-specific, excludes dashboard reviews from eligible device/source bindings
and advertises bindingFields. activeSubject matches an exact stored review focus;
selected devices get CONTEXT_RESOURCE roles and neutral UI_CONTEXT selection
metadata. No match means no fabricated subject. This is factual context and schema
lowering, not semantic routing or another question blacklist. Intent selection and
raw request/history remain model-owned. The v2 follow-up has205 passed AI-service
offline tests (three Docker classes excluded), including all output modes and the
new reference/subject checks. It has NOT been live-replayed or redeployed. The
running service remains the measured routing-v5 cohort version. No third paid
cohort was run beyond the predeclared two-cohort campaign.

Evidence: artifacts/a5-planner-verification-20261028-06/report.md, analysis.json,
before/summary.json, after/summary.json and usage-ledger-evidence.json. Remaining
A5/V/R/A3/UI/A6 acceptance stays open. Next: a fresh predeclared capped replay of
the explicit subject/per-field reference contract, then independent completeness
and preservation work in the dashboard skill.


## A5 context-first clarification: campaign07

The common trust policy now resolves exact message, mandatory dialogue, stored
focus and confirmed facts before asking. A short follow-up need not repeat its
established subject or supplied values. A question is warranted only if remaining
ambiguity changes the safe next action. planner-routing-v7 applies this to ASK
and keeps explicit new workflows possible. The common policy digest now enters
the pinned runtime fingerprint. This is prompt/context guidance, not semantic
server classification or another keyword/regex rule.

Previously offline-only planner-references-v2 (field-specific bindings, activeSubject,
context-resource roles) and the new clarification policy were deployed and tested
with the unchanged two frozen original dialogues in STANDARD: corrections ON,
model judge OFF.206 AI-service offline checks passed (three Docker classes
excluded), including preserved explicit new-workflow selection across all modes.

Actual campaign07 FAILED: first-pass2/5, recovered1/3 initially unsuccessful steps,
final3/5 and1/2 full dialogues. Rename still asked device-versus-dashboard and
requested the supplied name. Later edit failed exact name and door-history role/
view checks. Separate three-card create/move passed after one STEP format
correction. No planner UUID/resource-kind error appeared in this sample, but no
semantic-quality improvement is established. Separate no-rescore audit found
history:doors omitted from the first request despite its permissive formal PASS.
Prior criteria/scores and historical live-12 remain unchanged.

A2 durable ledger:10 calls (9 primary,1 format correction),97,488 input tokens
(21,632 cached),35,799 output (32,832 reasoning included),186,978ms model latency.
All usage is present; no judge/domain/outcome recovery calls. USD1.00 is the
charged/reserved bound, actual billing UNKNOWN_UNPRICED. No dashboard mutation;
both owned fixtures cleaned. Source stayed fixed through generation; provider/
model/output settings and STANDARD were unchanged at postflight.40-call/20-minute/
USD4 reserved caps were retained; one actual generation cohort was run.

An initial service-restart preflight503 occurred before model/fixture initialization.
Its BLOCKED report was preserved, and a zero-usage link retained the same wall
clock/caps for the actual live cohort. Shared wall348.1s, generation wall198.3s.
No failed generation or trial was retried or removed. Evidence:
artifacts/a5-active-subject-verification-20261029-07/report.md, analysis.json,
live/summary.json and usage-ledger-evidence.json. Remaining A5/V/R/A3/UI/A6 gates
stay open. Next: examine/compare planner conversation representation and routing
using the exact failure, plus separate STEP completeness. Installing an agent
framework or adding more instructions is not itself evidence of quality.

## 99. Configurable-platform contract alignment

- [ ] 99.1 Apply the added generic-resource requirement to pending implementation/integration; verify exact VCL/schema/source/receipt contracts and preserve original checked-task evidence and quality gates.

## 100. Requirements, consultation and retrieval integration

- [ ] 100.1 Move embedding/retrieval I/O out of PrimaryModelTransport serialization; reserve and account each embedding/reranker/indexing call with exact/unknown usage.
- [ ] 100.2 Integrate ASSISTANT-COLLAB/1 requirements/consultations and optional knowledge ports without overwriting current assistant-v2 contracts, default gates or persisted dialogue.

## 101. Second audit: accuracy and unambiguous execution

- [ ] 101.1 Integrate selective delegation, minimal sufficient role contexts, checked schema projections, assignment/provenance guards and ID-only private candidate retrieval from specification revision2.

## 102. Spring and Spring AI implementation bindings

- [ ] 102.1 Inventory pinned Spring AI2.0.2 API/dependencies, preserve current provider HTTP fixtures and inject Clock/ObservationRegistry through focused configuration factories (S0).
- [ ] 102.2 Implement pure PreparedExchangeFactory, existing-ledger dispatch claim/permit, PreparedManifestAdvisor(order0), ReservedDispatchAdvisor(order100), and replay before SDK creation; verify SPR01-SPR04.
- [ ] 102.3 Wire Boot ObservationRegistry into ChatClient/provider/embedding builders and add the narrow AssistantBoundaryObservationAspect on real application beans; verify SPR05/SPR06/SPR15 without duplicating durable trace events.
- [ ] 102.4 Explicitly disable auto tool/schema-validation retry loops, separate canonical Jackson2 validation from SDK mapping, and verify all supported output/provider fixtures under SPR03/SPR04/SPR10/SPR11.
