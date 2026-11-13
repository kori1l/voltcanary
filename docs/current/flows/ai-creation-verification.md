# AI creation verification — 2026-10-20

## LangGraph ordinary-API Docker lifecycle — 2026-11-12

Run the application and observability profiles without removing volumes:

```powershell
docker compose -f docker-compose.yml -f deploy/docker-compose/hsl.yml --profile app --profile observability up -d
python scripts/ai-langgraph-workflow-docker-smoke.py --restart-service --output artifacts/langgraph4j-workflow-run/summary.json
```

Use a new output directory for each trial; preserve failed reports. The command
requires the local generation gate enabled and STANDARD or FIRST_PASS_DIAGNOSTIC
without a model judge. Production defaults remain off. Docker must reach the
host fixture endpoint at `host.docker.internal:8128`; the script accepts an
explicit fixture URL/port for other local configurations.

The script creates its own synthetic account/provider and an actual vehicle
with recorded test telemetry through ordinary signed APIs. It does not change
the demo account or use a real model. The deterministic OpenAI-compatible
endpoint drives the actual Spring AI exchange and LangGraph nodes. It verifies:

- A persisted domain clarification and identical question after AI-service restart.
- Exact immutable review, request replay without a provider call, rejected stale
  confirmation and confirmed creation with exact owner draft readback.
- Original receipt reuse after another restart, with no second dashboard.
- An explicit valid UNSUPPORTED edit result leaves the created draft unchanged.
- A fresh explicitly selected-base edit retains the same dashboard ID and changes
  only its name, checked against an independently copied original definition.
- Exact saved update, confirmation replay and an inventory containing only the
  one owned dashboard in the isolated account.

The run archives its own dashboard, decommissions its own device and deletes the
temporary provider. Credentials, source tokens and provider requests remain in
memory; ignored evidence includes source/harness hashes, synthetic definitions,
IDs, typed failures and check outcomes. No raw provider payload or credentials
are written. A fixture contract correction must not be counted as AI recovery or
erase the earlier failed run. This qualifies integration only; existing A5/A6
semantic, native-tool model-quality, UI and rollout gates remain open.

## Stage4 scenario follow-up, 2026-10-26

The bounded stage4 corrective pass preserves failed dashboard task context,
constrains strict dataRefs by current role-compatible choices, adds short field
hints and safe provider usage counters, and prioritizes genuine source ambiguity.
This is not stages5/6 rollout or final model-quality acceptance.

New independent runs on the designated demo account: baseline1/2 dialogues,2/5
turns; corrected12-case sample9/12,24/28; separate field-hint sample0/4,5/9.
Keep all raw failures and earlier26/50,75/104 historical results unchanged.
Remaining cases identify wrong field interpretation, collateral titles/order,
incompatible replacement data and a too-strict derived-period grader mask.
A separate period audit preserves all widgets/layout/scope/name while changing
only the requested global period and its derived per-widget time presets.

Final source-question check1/1,2/2 passed. Compound real-model review and
confirmed private creation passed43/43 checks: exact definition, recovery,
idempotent execution and unchanged existing dashboards. Shared preview1100/420px
shows48px cards without clipping. The editor still clips values by3.5px with4px
overflow; this is an explicit stage5 visual acceptance failure, not a pass.
QA draft archived, own fixture decommissioned, temporary preview HTML removed.

Checks: AI165 including PostgreSQL, frontend410/typecheck/build, harness37,
structure and strict OpenSpec passed. Reports, per-turn safe history/trace exports,
source fingerprints and screenshots live under ignored artifacts/stage-four-*.
Detailed report: artifacts/stage-four-scenario-report-20261026.md.
Stage5 remains gated by completing the C3 created-device continuation acceptance;
stage6 and default enable remain deferred.


## Observable assistant trace acceptance (2026-10-25)

The shared trace recorder stores versioned, bounded observable events correlated
with submitted messages, turns, provider attempts, reviews, model runs and action
receipts. The inspector and authenticated JSON export work in the shared assistant.
Neither private reasoning nor raw provider bodies/headers are recorded.

Backend verification passed 155 AI-service tests, including PostgreSQL migrations,
concurrent conversation delivery and trace reads. Six focused trace tests cover
pre-review rejection, exact failed fields, redaction, truncation, paging, retention,
restart, scoped partial-receipt recovery and recorder failures. An unavailable
required start prevents side effects; a lost outcome blocks the next side effect
until its checkpoint can be stored, and never repeats a completed command.
The full frontend suite passed 409/409. Focused checks passed 59 tests, including four inspector/progress/export
regressions; typecheck/build, structure and strict OpenSpec validation passed.

Docker acceptance used `scripts/ai-assistant-trace-docker-smoke.ps1` and the isolated
`scripts/ai-trace-fixture-provider.py` through ordinary authenticated provider APIs.
Missing context was diagnosed as CONTEXT_READ / CONTEXT_HTTP_404 before any review.
A genuine HTTP provider call completed, then SANITIZATION rejected apiKey/dataRefs;
its candidate never became READY. Stored/exported payloads excluded the fixture
credential and private reasoning; paging and cross-account denial passed. These
fixture results measure trace capture, not real-model quality. Reproduction and
cleanup commands are in [reviewed-task operations](../operations/ai-reviewed-tasks.md#inspecting-an-assistant-turn).

A real-model browser continuation of the preserved bus conversation also reproduced
the reported failure. Turn `eeba21f1-29a7-4bbc-a939-2068bb498941` had no selected
device, then proposed invented bus-speed/bus-location/bus-occupancy dataRefs.
Compilation rejected the first candidate as `Expired or unavailable dataRef`.
The second provider attempt failed with MODEL_OUTPUT_LIMIT. Both attempts and the
final PREPARATION_FAILED outcome remained inspectable after reload; no dashboard
was confirmed or created. Browser technical inspection and export were exercised.
This is diagnostic evidence for C3/stages5-6, not a successful grounding result.

Ignored evidence: `artifacts/ai-assistant-trace-docker-summary.json`,
`artifacts/ai-assistant-missing-context-trace.json`,
`artifacts/ai-assistant-rejected-field-trace.json`,
`artifacts/ai-assistant-bus-continuation-trace.json` and
`artifacts/ai-assistant-trace-browser.png`.

## Complete conversation history (2026-10-25)

The conversation APIs persist complete chat history across page, sidebar and
floating modes. Authorization uses the actual `/accounts/current/scope`, including
receipt reconciliation, rather than the model-provider configuration owner ID.

Validation: full AI-service reactor tests passed, including PostgreSQL Testcontainers
and nine focused conversation regressions. A PostgreSQL race of twelve duplicate
requests accepts one start. Full frontend tests passed 405/405; typecheck/build,
structure checks and strict OpenSpec validation passed. Tests cover message IDs,
ordering/pagination, duplicate delivery, revision races, redaction, retention,
cross-owner denial, interrupted requests, immutable receipts, identity changes,
independent drafts and restoration without provider/tool calls or auto-answer timers.

Repeatable authenticated Docker API acceptance:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/ai-conversation-history-docker-smoke.ps1 -RestartService
```

The script creates isolated accounts, initializes their ordinary account scope,
submits/replays a request, verifies pagination, 409 for stale revisions, 404 for
another owner, credential redaction, message IDs/order and selected-chat recovery
after restart. It never calls domain execution. Evidence is in ignored
`artifacts/ai-conversation-history-docker-summary.json`.

Browser acceptance used normal signed-in review/confirmation to create the isolated
`bus22-history-20261025` without connecting a source. Conversation
`091b8794-3e63-4b8b-b496-a14bab8cc7c7` survived reload during preparation, service
restart, reopening in a new tab and all three window modes. Its input, original
reply and separate SUCCESS receipt remained ordered. An unsent draft survived
reopening and was then cleared. DB inspection confirmed one provider call,
one successful action, one device (`91227ae4-3188-42c3-b2b4-d28926bfdb73`) and one
creation receipt. Recovery created no duplicate. This named test device remains
available for continuation checks. Evidence: ignored
`artifacts/ai-conversation-bus-history-summary.json` and
`artifacts/ai-conversation-history-browser.png`.

Previously lost transcripts cannot be reconstructed; legacy pointers offer a
labelled recovered task. Retention is independent of reviewed-task audit records;
see the [API contract](../api/ai-agent-api.md#complete-assistant-conversations).

## Widget catalogue composition correction — 2026-10-22

New requests use `creation-v6` / `dashboard-intent-v3` and compose the real widget
catalogue without choosing a task template. The owning read-only composition
preparation validates complete widget lists, registered metric/unit pairs,
authorized device subsets and explicit map sources. The genuine `vehicle-map`
can coexist with time-series widgets; `location-matrix` is not a map. Each widget
may use its own relative period; map history remains bounded to24h. Legacy
prepared template tasks still execute through their original preparation path.
Clarification reconstructs widget hints from old template definitions, renews refs
and preserves the exact new reviewed composition for confirmation.

Validation: host Maven modules passed75 dashboard tests and48 AI tests, including
real PostgreSQL Testcontainers. Inside Java21 Compose, focused preparation/compiler
tests passed15 and20 respectively. Full suites inside app containers cannot start
nested Testcontainers because the Docker socket is not mounted; that infrastructure
failure is not counted as a product test pass. Frontend typecheck and build passed.
Full frontend host and Docker runs each passed344/345 tests; the existing ML
older-compatible-feature-snapshot workflow failed. A separate isolated ML Docker
run passed that test but timed out in another existing broad workflow (22/23).
Final map/preview/business-renderer tests passed16/16; the new compact map keeps
GPS/journey fields collapsed and the follow checkbox inline in dashboard widgets.
The ML failures are retained as validation limitations, not silently excluded.

Live evidence is under ignored `artifacts/ai-composition-live-2026-10-22/`.
The configured `gpt-5-nano` prepared one dashboard for HSL bus0012-02214 with a
genuine map, speed chart and heading chart, without template selection or incidents
and maintenance. The map rendered actual tiles and the last recorded marker,
explicitly stale. No readings were injected. An empty6h interval correctly showed
no chart data. One clarification hit the configured provider timeout (~60s);
the existing review and reply text survived and creation stayed disabled for retry.
Final live acceptance passed: gpt-5-nano refined to LAST_24_HOURS with two
half-width charts first (height4) and the full-width map below (height6). Plan
86c42bbc-ae65-4d0d-84f7-12a4a61568f5 was confirmed into private dashboard
9bcdf570-b3fa-4d95-af02-2f08bc6fee08. The persisted draft definition equals the
reviewed definition exactly (all widgets, queries, bindings and responsive layouts).
The ordinary editor showed actual chart readings and the map trail, with the same
stale-position warning and1000-point history bound. No telemetry was fabricated.
See artifacts/ai-composition-live-2026-10-22/acceptance.json and screenshots10–12.
The final full frontend run passed348/348, superseding the earlier flaky ML failures.

Compose infra/app/observability profiles were started together. This run uses
Timescale host15433 and cAdvisor18089 because5433 was occupied and cAdvisor's
default8089 conflicts with dashboard-service. Service-internal addresses are unchanged.

## Conversational window and faithful dashboard preview — 2026-10-22

Implementation: `redesign-ai-assistant-dashboard-preview`. The assistant uses a
light independent window (floating, overlay sidebar, expanded), explicit context
attachment and one composer for requests/refinement. Mode, position, minimize and
ordinary route changes retain the mounted conversation and unsent message. Browser
reload retains presentation preferences and the last authorized recovery task ID,
not conversation text. Dashboard creation confirms the currently displayed task;
an unsent or pending refinement disables the old creation control.

The `creation-v5` prompt / `dashboard-intent-v2` contract accepts bounded ordered
template panel keys and sizes. `dashboard-layout-intent-ru-v2.json` is a seven-case
Russian compiler fixture: four supported layout selections and three malformed or
unsupported model outputs. `DashboardPanelIntentTest` feeds these outputs through
the actual compiler. It is deterministic adapter evidence, **not a live model score**.
Additional tests verify strict fractional-version rejection, selection preservation,
desktop/tablet/mobile packing, invalid keys/dimensions, digest stability and exact
reviewed definition submission. Existing 40-case dashboard intent fixtures also pass.

`POST /dashboards/preview/query` was verified through the ordinary evidence pipeline
with real authorization/scope renewal and no dashboard storage calls; access revocation
returns no-access instead of cached values. The frontend submits the exact reviewed
definition and renders it using DashboardCanvas/WidgetPanel. Late preview/session
responses are ignored; no-data is not replaced with sample values.

Validation evidence under ignored `artifacts/`:
- Full frontend: 118 files / 344 tests passed with two workers. An initial concurrent
  run had a timing failure in the unrelated older-snapshot ML training test; that
  file passed independently (23 tests), then the full bounded-worker run passed.
- Final focused frontend: 10 files / 39 tests, plus typecheck and production build.
  The existing large-chunk warning remains; the dashboard preview is lazy-loaded.
- Both changed Maven service suites passed: 46 AI tests and 89 dashboard tests,
  including H2 and real PostgreSQL Testcontainers. The installed host JVM is JDK 25;
  this is not evidence from the main Java 21 Compose deployment.
- Strict OpenSpec validation and structure checks passed. Structure checks retain
  existing module-size/wildcard warnings unrelated to this change.
- Browser inspection used actual React components and an isolated response fixture:
  floating/expanded modes, the ordinary no-data canvas and a same-composer refinement
  removing the events panel were observed. Temporary fixture entrypoints were removed.
  This verifies rendering and interaction, **not live owning-service/provider behavior**.

Live Compose Java 21 acceptance subsequently passed on 2026-10-22 using the
existing authenticated demo browser session and its configured `gpt-5-nano`;
`fallbackUsed=false`. Timescale's occupied host port 5433 was overridden to 15433
for this run only; internal service addresses and local environment files were unchanged.
The named My devices request selected the existing one-source training fridge
explicitly through the context picker. The initial review contained power-history
FULL/6 followed by power HALF/4 and incidents HALF/4. A same-composer reply removed
incidents, placed power left and history right at HALF/4, and changed 24h to 30d.
Name, equipment, source and model stayed unchanged. Desktop DOM placements were
1/span6 and 7/span6 on row1/span4; phone placements stacked at rows1 and5.
Real preview reads showed the existing recorded power and explicit no-data for
the empty 24h history. The 30d history returned recorded points.

Reviewed plan `d45957d4-8f1c-44a5-ace1-3702758aeea6` created private draft
`89f4cf29-2625-487d-8f9f-b3c952593472` only after the creation button was clicked.
Canonical JSON comparison of its persisted complete definition with the saved
reviewed action definition passed, including all responsive layouts and bindings.
The ordinary editor displayed both resulting widgets and genuine evidence.
The account dashboard count was18 before confirmation and19 afterwards. Unsent
and pending refinement disabled the previous review's creation button.
Ten focused AI tests and ten dashboard template tests also passed inside the
running Java21 containers. This targeted live run is not a new full model benchmark;
unknown-output, provider-failure, stale-review and foreign-resource behavior retain
the separately recorded automated evidence above.

The live run also exposed a remaining UX/model limitation: the six refrigeration
demo units have three sources and correctly require clarification, but a free-text
reply replacing the first unit with the one-source training fridge repeated that
source question. Starting a new conversation with the context picker succeeded.
Do not count that free-text equipment replacement as accepted behavior.

Switching the retained successful conversation to floating and left-sidebar modes
after opening the ordinary editor also passed; the left sidebar measured480px
at x=0. The original floating-right preference was restored after verification.

Ignored evidence: `artifacts/ai-assistant-live-2026-10-22/` contains16 numbered
workflow screenshots, `reviewed-plan.json`, `created-definition.json`,
`acceptance.json` and the screenshot report. Java21 test logs are
`artifacts/ai-assistant-live-java21-tests.log` and
`artifacts/ai-assistant-live-java21-dashboard-tests.log`. Credentials and tokens
are absent from these reports. No publication or default-dashboard change occurred.

Identity hardening prerequisites were verified separately; see
[security inventory](../architecture/08-security.md). These checks use signed
JWTs and real account IDs, including fixtures where account ID differs from user.

## Isolated reviewed execution

- AI Maven suite: 12 tests, zero failures/errors (`artifacts/ai-isolation-verified.log`).
  After typed configuration wiring was finalized, PostgreSQL durable-state,
  reconciliation and frozen intent tests passed again (4 tests,
  `artifacts/ai-isolation-final-check.log`). PostgreSQL runs in Docker Testcontainers.
- Owning device PostgreSQL suite: 6 tests passed, including eight parallel HTTP
  POSTs yielding one resource/receipt, conflicting payload, separate users,
  rejected-location rollback, decommissioned replay and disabled account.
- Frontend: 111 files / 321 tests passed with two workers. Focused AI/session/
  recovery tests after additions and StrictMode correction: 4 files / 11 tests
  passed. Typecheck and production build passed; existing chunk-size warning remains.
- Main Docker Java 21 stack: `ai-task-isolation-docker-smoke.ps1` passed ten
  authenticated gateway checks (foreign read/execute, forged identity, stale
  version/digest, missing confirmation, nested credentials, foreign provider and
  foreign location). Rejected requests left the task READY.
- Browser: prepare, cancel, close/reload/recover the same task, confirm, SUCCESS,
  disabled repeat-execute, and open actual device configuration verified. Browser
  testing found and fixed effect cleanup under React StrictMode; the regression
  test executes a versioned plan under StrictMode.
- OpenSpec strict validation and structure check passed. Reports and screenshots
  remain under ignored `artifacts/`; no generated evidence or credentials are committed.

## Configured small-model run

Frozen fixture `device-intent-ru-v1` contains 40 Russian requests (20 clear,
10 ambiguous/missing, 10 unsafe/unsupported). Its deterministic provider-stub
test passes through the actual sanitizer and typed compiler. Live acceptance
on configured **GEMINI / gemini-3.5-flash-lite**, temperature 0, prompt
`creation-v1`, schema `device-intent-v1`, passed **20/20 clear** without a
clarification and **20/20 unresolved cases non-executable**. Preparing all cases
created **zero devices**. The report records the fixture SHA-256, task IDs,
per-case latency, actual selected model and fallback evidence. No alternate
model was selected during evaluation. Requests are paced to respect provider limits.

The initially configured `gemini-2.5-flash` was explicitly rejected by the
provider as unavailable to new users despite appearing in its model listing;
it was not scored as a successful run. Initial Windows PowerShell response
decoding and request pacing were corrected before the recorded final run.
The final report is `artifacts/ai-device-model-evaluation.json` (PASSED).
The test credential was submitted through ordinary provider configuration APIs;
reports and tracked fixtures contain no keys, tokens or real private inputs.

## Device release checks — 2026-10-21

Full device/AI reactor passed (61 device tests and 13 AI tests). Additional
gateway/identity/AI validation passed (25 gateway, 30 identity, 16 AI tests plus
shared-security tests); final focused compiler/context/executor checks passed.
The owning PostgreSQL migration and HTTP tests run real PostgreSQL containers.
The main Java 21 Docker stack passed six parallel execute requests, stable
replay and zero implicit sources, then passed the same checks after restarting
the AI container (`ai-device-after-restart-summary.json`). Browser success and
ordinary source setup with no samples/source were verified; generated screenshots
are kept under `artifacts/`.

The repeated frozen live run after restart passed **19/20 clear** and **20/20
unresolved**, with zero devices created by preparation. This exceeds the frozen
18/20 threshold. One response inferred a manufacturer from a brand-like device
name; the compiler now requires a separate manufacturer/model cue or explicit
context field. A regression test covers both the brand-like name and an explicitly
supplied manufacturer/model. The final paced report is
`artifacts/ai-device-final-model-evaluation.json`; the earlier 20/20 run remains
separate evidence. The evaluation counts decoded JSON arrays correctly on Windows
PowerShell and reads both fixture and HTTP bodies as UTF-8.

With explicit user authorization a separate OpenAI provider was configured in
the test account using `gpt-5-nano`, a small model listed in the
[official OpenAI catalogue](https://developers.openai.com/api/docs/models/gpt-5-nano).
Its first provider call succeeded in 8.2 seconds but exceeded the gateway's old
five-second response timeout. The AI route now has its own bounded 90-second
timeout; other service routes are unchanged. This probe is separate from Gemini's
measured 40-case acceptance, and does not claim an OpenAI quality score.

Final Java 21 Docker gateway preparation on `gpt-5-nano` passed in 14.3 seconds
with no fallback or mutation (`ai-openai-nano-gateway-summary.json`). The exact
brand-name regression also passed live after the compiler fix. Final frontend
Docker checks passed: 112 files / 323 tests, typecheck/build; the subsequent focused
AI regression run passed 12 tests and typecheck/build. The final PostgreSQL
suite passed seven tests with real outbox enqueue enabled and dispatch mocked:
a forced receipt INSERT failure after device flush rolled back device/outbox,
then retry committed one device/event. The ordinary eight-way concurrent test
also asserted one outbox event.

After restarting Vite to clear stale Windows bind-mount transforms, browser
recovery/status left READY executable, the first confirmation produced SUCCESS,
and the result opened the ordinary configuration route. All three generated
device fixtures were decommissioned through their owner's authenticated API;
no sources or telemetry were created by these tests. Recorded plans remain
scoped and expire through normal retention. No guideline/AGENTS changes were
needed for this existing workflow.

## Dashboard creation — 2026-10-21

All backend modules were checked in an isolated Java 21 Docker container with
real PostgreSQL/Timescale/Kafka Testcontainers where required. The final module
sets total **591 tests**, with zero failures or skipped tests. AI passed 28 tests,
dashboard passed 67, telemetry 82, analytics 135 and alerts 51. The dashboard
PostgreSQL regression reads an actual prepared private draft, preserves an
accepted zero, verifies cache reuse, hides foreign queries and removes cached
data immediately after the binding loses access. Initial failures in the new
test's incomplete owner response and changing equipment metadata were corrected;
the final PostgreSQL ten-test set and full dashboard suite passed.

Frontend Docker validation passed **113 files / 327 tests**, typecheck and
production build. Existing ML warning-key and bundle-size warnings remain.
The ordinary assistant review shows named panels, equipment, a readable period
and private draft scope. A separate `gpt-5-nano` browser request prepared a valid
Refrigeration review; opening and cancelling confirmation did not execute it.
This probe does not claim an OpenAI 40-case quality score.

The frozen dashboard fixture keeps the same 40 Russian requests, expected results
and thresholds (18/20 clear and 20/20 unresolved). Prompt v2 scored 11/20 clear;
v3 scored 17/20 clear and 20/20 unresolved, with zero resources created. One v3
request received a provider 503; two others confused explicit equipment selection
with catalogue candidates. These failed runs remain separate evidence and are
not accepted. Prompt v4 now carries explicit authorized selected-device refs,
distinct from mere candidates. A regression verifies both contexts. Provider
errors count as incorrect requests in their original denominator; they are never
discarded or replaced by a stronger model.

The final v4 live run on **gemini-3.5-flash-lite passed: 19/20 clear, 20/20
unresolved, zero devices and zero dashboards created**, with no provider failures
or fallback. One clear request confused the requested dashboard name with a
template and remained non-executable; it counts as an error. Evidence is
`artifacts/ai-dashboard-model-v4-evaluation.json`, prompt `creation-v4`, schema
`dashboard-intent-v1`, templates v1 and fixture SHA-256
`FE10678A45EEA54E805B169C4A113FB4290F994ACBAA9F7118E6C15BC1F4CEBA`.
No automatic repair or stronger-model substitution was used.

The real Compose execution smoke passed **15 checks** with current JWTs and two
accounts: mixed/foreign device/source denial, unknown ownership fields, template
version drift, foreign task lookup/execute, cancelled/stale reviews, six concurrent
confirmations and stable replay. Scoped fault injection committed the second
reviewed definition with its actual stable domain key, marked only that fresh
task/action UNKNOWN and restarted AI. Confirmed recovery returned the same
original draft through the read-only receipt. Both remained unpublished and
personal preferences were unchanged. Created dashboards were archived and the
foreign device decommissioned; cleanup is recorded in
`artifacts/ai-dashboard-execution-docker-summary.json`.

Ordinary widget reads preserved accepted numeric zero. An initial smoke assertion
incorrectly treated a latest-asOf snapshot as interval history; its failed log is
retained. The corrected check uses the normal draft editor to set a history panel
to an empty fixed interval, then verifies explicit no-data without observations.
The final full scenario passed on fresh reviewed tasks, including another restart.

Browser acceptance with `gpt-5-nano` completed: cancel, status check after the AI
restart, explicit confirm, SUCCESS and the real ordinary editor. The editor showed
the accepted 4 C reading, power history and honest empty incident/maintenance
panels. Published revision stayed zero. The last UI fix marks a durably prepared
request saved for the existing navigation guard; seven focused guard/AI tests,
typecheck and build passed in Docker after the full 327-test baseline. A fresh
browser page recovered SUCCESS and opened the same editor without another
confirmation or an unsaved-form warning. The browser draft was archived and the
synthetic fridge decommissioned through normal owner APIs. Evidence:
`ai-dashboard-browser-summary.json`, `ai-dashboard-cleanup-summary.json` and
`ai-dashboard-browser-editor.png` under ignored artifacts. No credentials or
generated evidence files are included in versioned sources. No AGENTS/skill
updates are needed for these existing project workflows.

## Real HSL device handoff — 2026-10-22

The demo owner's AI provider was absent; account-specific OpenAI gpt-5-nano is
now the default. A provider configuration test alone is not a live model test:
real planning returned OPENAI/gpt-5-nano with fallbackUsed=false. Existing Rules
plans remain immutable; refresh the page and prepare a new plan after configuration.

The request for one named bus and "несколько свежих измерений" exposed a quantity
guard that matched unrelated plural observations. It now binds quantities to
device nouns. Multiple actual device requests and multiple model actions still
require clarification. Location remains optional. Integration details do not
authorize an extra model-generated source or connector deployment; the reviewed
first step creates one device and explicitly leaves source connection separate.
Unsupported rule requests no longer invent empty YAML validation. Explicit
nonempty YAML validation remains supported.

Browser acceptance under the demo owner prepared the long HSL request, cancelled
confirmation (zero target devices), confirmed it (exactly one VEHICLE), and opened
the ordinary source setup. The API/REST source hsl-bus-0012-02422 was created in
Configuration and assigned the existing HSL transport v3 template. Owner APIs
added HSL attribution metadata and an explicit private connector binding, preserving
all16 existing entries. The optional HSL connector had been stopped; the app
profile alone does not start it. It was explicitly started with17 bindings.
No simulator configuration/run was created. Replaying the reviewed plan returned
the original SUCCESS without a second device/source.

Nine distinct accepted coordinates were stored for the new bus, initially FRESH.
Its last observed point was2026-10-21T22:59:09Z; subsequent silence correctly
became STALE. No automatic continuation, extrapolation or fabricated readings
were used. Existing bus0012-02418 on route600 remained FRESH during final acceptance;
it is the live alternative when bus0012-02422 is not publishing. Fuel, ignition
and odometer remain null for HSL. Broker subscription and observation activity
prove current transmission only, not continued future availability.

The UI checks also fixed a saved-plan drawer navigation blocker and Movement's
simulator-only source filter. Persisted plans can close/open their ordinary result;
new unsubmitted edits remain guarded. Vehicles context retains /vehicles. Movement
now accepts authorized REST API/sensor/simulator sources, requires selection when
ambiguous, and links real source setup to Configuration.

Docker validation: AI-service31 tests (including PostgreSQL durable boundaries),
HSL connector9 tests, frontend115 files/334 tests, typecheck/build all passed.
The initial unrestricted frontend run hit a pre-existing ML test's5s timeout;
bounded two-worker full runs passed. Focused navigation/source tests also passed.
Ignored reports: ai-hsl-handoff-acceptance.json, ai-hsl-original-request-final-plan.json,
ai-hsl-backend-docker-tests.log, ai-hsl-frontend-complete-docker-tests.log and
ai-hsl-connector-docker-tests.log. This targeted handoff validation does not claim
a new full40-case model benchmark. No credential is stored in tracked evidence.

## Clarification replies — 2026-10-22

Both the full AI page and assistant drawer expose "Ваше уточнение" beneath a
question. "Продолжить с уточнением" submits an answer; READY plans also offer
"Уточнить план". This prepares a new review, not execution. The original request,
explicit equipment, resolved provider/model and bounded previous answers remain
available after task recovery. Previously saved tasks without continuation metadata
recover explicit equipment from stored references, not from catalogue candidates.

The server refetches authorized catalogues and scopes every parent/context read
to the current owner. Parent plans remain unchanged. Unsupported/expired/executed
parents are rejected; six replies and8000 cumulative message characters bound the
conversation. Later explicit name, template and period preferences can replace
earlier preferences. Old opaque model refs are intent hints only and are replaced
with display names before re-planning. No authority is inherited from model text.

Answers stay editable after HTTP/provider failures. The old review cannot execute
while a replacement is pending, new unsubmitted text stays guarded, and responses
from an obsolete request/session are ignored. Root request edits start a new task;
replying does not require rewriting that root request.

Docker verification passed42 AI-service tests, including PostgreSQL continuation
metadata isolation/recovery and the unchanged40-case intent fixtures at the compiler
boundary. Frontend116 files/339 tests, typecheck and build passed. This is distinct
from a new live40-case model benchmark, which was not run for this UI change.

The demo owner's gpt-5-nano live check passed10 assertions: initial missing-name
question, plain-name answer producing a new READY plan, pinned model and equipment,
a second reply changing name and24h→7d, immutable parent, invalid/unsigned/closed
task rejection and zero device/dashboard creations. The initial run exposed a
repeated name question; clearer answer-role instructions and a quoted answer block
fixed it. A separate run correctly asked about ambiguous sources on equipment with
three registered sources; the name/period acceptance used an existing one-source
training fridge instead. Its resource count and source configuration were unchanged.

Ignored evidence: ai-clarification-live-summary.json, ai-clarification-live-smoke.log,
ai-clarification-backend-complete-docker-tests.log and
ai-clarification-frontend-complete-docker-tests.log. Earlier nonpassing probe outputs
are retained separately and are not counted as acceptance.

Browser acceptance additionally answered the name/template question, received a
legitimate configuration question for Energy, then clarified back to My devices
and reached READY with the supplied name. Reload plus Recover latest task restored
that review; the drawer recovered the same task and opened its next reply form.
No confirmation/execution was submitted. API recovery after an AI-service restart
and a foreign-owner clarification request (404) were also verified. Screenshots:
ai-clarification-question-reply.png, ai-clarification-ready-review.png and
ai-clarification-drawer.png under ignored artifacts. Existing repository workflow
and validation commands remain applicable; no AGENTS/skill updates are needed.

## Unbounded response wait and visible thinking — 2026-10-22

Removed response read deadlines from all four configured-provider adapters and
legacy OpenAI. The model timeout setting now bounds connection establishment only.
The AI gateway route defaults to response-timeout=-1 (running Compose environment
verified). Provider/model IDs and reasoning parameters were not changed.
The overlay shows a spinner, “Модель думает…” and elapsed m:ss above the composer;
recovery/read execution show their actual client phases. No invented server-stage
progress or internal reasoning is shown. Minimize/reopen retains the same request.

The real provider request completed after61.1s with an unparsable response rather
than a socket read timeout. Its previous preview and refinement text were retained.
An additional response guard distinguishes OpenAI max_output_tokens exhaustion
from malformed output and returns safe MODEL_OUTPUT_LIMIT diagnostics. The live
retry completed successfully in42.581s, then exact reviewed creation passed.
Token exhaustion is not claimed as the diagnosed original cause. The original
61.1s response proves the provider response was received after the former60s
read deadline; the95s/minimize behavior is automated UI evidence, not a live
provider-duration claim.

Validation: full frontend348/348 tests passed in Docker, including the previously
failing ML test. Typecheck and build passed (existing large-chunk warning remains).
The new fake-clock95s conversation test covers minimize/reopen, exactly one request,
late response acceptance and status cleanup. A real local HTTP provider test delays
its response beyond the configured connection timeout. AI host full51 tests and
gateway25 tests (plus upstream reactor modules) passed, including PostgreSQL-backed boundaries. Java21 focused
AI tests passed16 before the additional incomplete-response test. Screenshot evidence
is in artifacts/ai-composition-live-2026-10-22 (06-thinking-start.png).


## Current vehicle speed correction — 2026-10-22

Authenticated fleet-position API evidence at20:03:14Z reported FRESH for HSL buses
0012-02417 and0012-02422 (observations20:03:10Z), and STALE for0012-02418,
0012-02214,0020-00004 and0012-02212. This is a point-in-time observation, not a
promise those vehicles remain online. The HSL connector was running and healthy.

Fleet rows now show current speed only for FRESH evidence while live refresh is
available. Stale rows show current speed unavailable and the full measurement
date. Shared map summaries hide old speed, including configured speedKmh fields,
and date the retained stale measurements. Recorded-history playback is unchanged.
Focused frontend19 tests, typecheck and production build passed (existing chunk
warning remains). Live browser evidence is recorded in artifacts/vehicle-freshness.png.

## Persistent task memory and timed defaults — 2026-10-23

creation-v8 receives taskMemory separately from the transcript. Compiled working
intent is persisted immediately in context_json._clarification.input, including
name, period, selected devices, widgets and layout. Questions and task recovery
retain these values; current resource references are renewed through authorized
catalogues. No phrase list interprets naming replies in the browser or compiler.
Missing required names can be filled from the remembered task value.

Clarifications declare missingFields and concrete scalar suggestedValues. The
browser displays those actual values and serializes a normal clarification;
it does not invent defaults or resend arbitrary model prose. Questions about
known fields, or malformed proposals, receive one internal model repair. If a
proposal lacks concrete values it cannot start a timer. Provider errors and
repeated defaults for the same field cannot generate an automatic loop.

A60-second timer starts for a new eligible question. Starting input or IME,
including input later erased, cancels it for that question. New missing fields
can receive their own timer. Automatic choices are labelled; mutation confirmation
remains explicit. Expanded thinking/default notices align with the composer.

Live gpt-5-nano acceptance: question0d7d5604-2671-4ef7-99a4-a50deff1f78a proposed
an actual name. After60 seconds without typing, review579fc967-557e-4959-b6a4-7bef9a0d1d45
used exactly that name. Refinement63d79371-985e-4378-be05-907a386e2604 moved the speed
chart above the map while retaining name, device, period and widget keys. Both
reviews stayed READY without execution. Browser reload and authorized recovery
restored the same name and layout with no question or default notice. Initial
probes exposed incomplete provider JSON and question-shaped suggestions; these
were blocked and the final structured-contract scenario passed.

Evidence: artifacts/assistant-memory-live-2026-10-23/README.md and acceptance.json.
Full frontend362 tests, AI56 tests and Java21 Docker15 focused tests passed.
Typecheck/build, OpenSpec and structure checks passed. Existing bundle-size
warning remains. Thinking notice and composer were each920px wide at the same
left coordinate in the tested expanded viewport.

## Widget capability correction — 2026-10-23
Added vehicle-value and vehicle-timeline through the ordinary authorized positions
pipeline, editor/palette and AI composition. Verified missing fields, boolean false,
zero speed, string stop, stale observations, source binding, history without latest,
shared reads, same-model schema repair and presentation persistence with regressions.
Full frontend suite passed367/367 before two additional regressions; final focused
suite20/20, typecheck and production build passed. Java21 Maven tests for AI-agent and
dashboard services with dependencies passed, including PostgreSQL integration.
OpenSpec strict validation and structure checks passed with existing size warnings.

An isolated browser harness used the actual DashboardCanvas/WidgetPanel renderers
with explicitly labelled synthetic observations, at desktop1440px and a420px container.
Measured SVG viewBox899x171 matched899x171 CSS pixels; narrow390x231 matched390x231.
Map title/follow/GPS rows were absent with their visibility controls disabled.
Screenshots and harness sources remain ignored in artifacts/widget-capability-review.
Temporary frontend harness entry files were removed after checking. A live provider
request with these new capabilities was not verified; the available saved local test
credential failed authentication and was not reset. This does not assert live
next-stop availability for the current HSL source; missing source fields remain unavailable.
No AGENTS or skill updates are needed: architecture and verification workflows remain unchanged.


## Stable review refinement — real-model acceptance2026-10-23
The original bus request and the user's literal name acceptance, top-row move,
rename/remove/shrink request and "Это хуже. Сделай как было до этого" were sent
through the real console to configured OpenAI nano/gpt-5-nano. The final sequence
preserved name, device, period, widget identities and all omitted settings. Moving
three cards to thirds left map/chart configuration and dimensions exactly intact.
Conversational rollback returned the exact earlier frozen definition and prepare
hash. Back/Forward read the saved versions; reload/recovery retained all four.
Evidence and18 passed comparisons: artifacts/refinement-live-2026-10-23/README.md,
acceptance.json, real server responses and screenshots06-chart.png/05-recovered.png.
No dashboard was executed or published. Initial live probes uncovered and fixed
sanitizer edit-field loss and duplicate aliases of the same device, now covered
by backend regressions. Refinements use a dedicated latest-change prompt and
server-side merge, not whole-dashboard regeneration.

Rendering a low legacy chart now expands readonly grid tracks to its readable
minimum220px card rather than clipping labels. Measured plot873x169.109px equals
its SVG viewBox. Full frontend370 tests passed before this final styling change;
11 focused canvas/history tests, typecheck/build passed afterwards. Full AI Maven
and subsequent focused reference regressions passed on Java21.

Remaining limits: status cards were already at the minimum2 rows, so70% further
shrink was not applied; the summary lists actual changes but does not yet explain
that no-op. Incidents were absent in the initial model response, so their removal
was a no-op; explicit existing-widget removal is covered by backend regressions.
The source was stale: this run does not claim fresh bus/stop telemetry. The generic
latest-value speed aggregate still displays0 while source-bound vehicle widgets
label retained observations as stale. A local signed fixture session was used;
no authentication bypass or provider configuration change was introduced.


## Compact value cards —2026-10-23
Supersedes the two-row minimum limitation above. The same real bus review was
recovered and the literal "Инциденты убери. Название виджета двери открыты мне не
нравится. И по высоте верхние виджеты надо сделать меньше на70 процентов" was
submitted to configured gpt-5-nano. It emitted height1 and compact:true for all
three top status cards, preserving name, scope, every source binding/query and
exact map/chart widgets. All three breakpoints contain one-row placements.
Ten server comparisons are recorded in artifacts/compact-live-2026-10-23/acceptance.json.
The resulting desktop card is48px high instead of108px: row quantization means
this does not claim an exact70% pixel reduction. Details are available through
panel information, and stale observations retain a visible short label.

Desktop cards measured291.7x48px; mobile cards measured341x48px at viewport420px.
For all six cards, scrollHeight equals clientHeight. Opening Information retained
full measurement time and provenance. Screenshots and13 passed comparisons are
in artifacts/compact-live-2026-10-23. Final full frontend372/372 tests, AI62/62 and
dashboard98/98 Java21 tests, typecheck/build passed. The first full UI run exposed
an incomplete new metric fixture (fixed) and a transient unrelated ML async test;
the focused reruns and the complete final run passed. The visual check also
removed a preexisting heading margin that clipped minimum-height content.


## Optional map footer —2026-10-23
The actual gpt-5-nano console received the request to hide stale-speed and last1000
points notices under the current map. The resulting READY definition differed
only by map presentation.showMapNotices=false; an exact whole-definition
comparison retained every other field, layout and binding. Screenshot before.png
showed the truncation notice; after.png and DOM checks show no footer strings
while attribution remains. Telemetry had become fresh during this run, so both
stale+truncated notices together were verified by a regression with stale evidence.
The map Information dialog still displayed measurement time/provenance.
Evidence: artifacts/map-notices-live-2026-10-23 (review.json, verify.py,
acceptance.json, screenshots). No dashboard was created. Frontend14/14 and
backend20/20 focused tests, typecheck/build, strict OpenSpec76/76 and structure
checks passed, with existing bundle-size and structure warnings.


## Shared widget settings — 2026-10-23
The implementation now derives editor controls and AI capability hints from the
same dashboard-domain descriptor. General contains title/header and evidence-detail
settings for all types; Appearance contains only supported grouped controls. Palette
visualization choices use service manifests. New composition settings are typed,
legacy presentation is retained, and incremental settings preserve omitted values.
Regressions cover common controls, manifest-provided choices, typed validation,
preparation and reset/merge semantics. At the user's subsequent request, execution
passed: frontend380/380 tests across126 files, Java21 AI74/74 and dashboard104/104
tests, typecheck and production build. Logs: artifacts/widget-settings-*.log.

Live Docker evidence in artifacts/widget-settings-live-2026-10-23 includes10 API
checks (semantic validation errors return422),36 catalogue/editor comparisons,
reload persistence and private publication of a six-panel QA dashboard. All six
published titles are visually hidden while accessible names remain; the editor
retains drag headers. The saved door label and automatic compact setting survived
reload. Map footer controls and notices are absent; map attribution remains.
Screenshots document General and the viewer.

The configured real gpt-5-nano received a request to hide every title and the map
footer. Its READY refinement passed21 comparisons: name, widget IDs/order, layout,
bindings, queries and unrelated settings were preserved. No dashboard was executed
from this AI plan. The separate QA dashboard was archived through the recoverable
owner API after verification. No prior tram replay failure is promoted to acceptance,
and this does not implement semantic refinement compilation.


<a id="multi-turn-conversation-evaluation"></a>

## Multi-turn conversation evaluation

The new harness is separate from the historical template-only acceptance script.
It evaluates the actual POST plans / POST clarifications / GET recovery path;
production prompts, compilers, confirmation and query behavior are unchanged.

### Existing coverage inventory

| Existing evidence | Covers | Does not establish |
| --- | --- | --- |
| ai-dashboard-model-evaluation.ps1 and dashboard-intent-ru-v1.json | Historical template creation/clarification and provider pinning | Whole conversational refinement or modern catalogue-composition quality |
| dashboard-layout-intent-ru-v2.json and Java intent/compiler tests | Deterministic layout/delta and validation behavior | Real-provider repeatability |
| DashboardIntentEditsTest and preparation tests | Omitted-field preservation, settings merge/reset and prepare invariants | Whether the model chooses the correct requested edit |
| DurablePlanPostgresTest and execution docker smoke | Scoped immutable plans, concurrent confirmation, receipts, restart/recovery | Whether a multi-turn request was understood correctly |
| Frontend conversation/preview/card tests | Rendering, history controls, compact values and failure UI | Real-model end-to-end conversation quality |
| Prior settings live acceptance | One actual settings refinement with21 assertions | Twenty-one independent successful model trials |

### Corpus and runner

The frozen corpus and JSON Schema are
`backend/services/ai-agent-service/src/test/resources/evaluation/dashboard-conversation-ru-v1.json`
and `dashboard-conversation-ru-v1.schema.json`. Corpus revision2 has25 scenarios, eight
critical sequences and52 turns per full pass. Two paraphrase cases are held out
from prompt tuning. Original user spelling remains in the core tram sequence.
Each case declares fixture profile, required capabilities, mutation policy,
cleanup and independent per-turn outcome predicates. Unknown predicates fail
validation instead of silently dropping an expectation.

Use `scripts/ai-dashboard-conversation-evaluation.ps1` or its Python equivalent.
Python uses only the standard library. Account metadata contains email, actual
accountId, providerId, modelConfigId and modelId, never passwords/keys/tokens.
Supply VOLT_EVAL_PASSWORD for ordinary login or a transient
VOLT_EVAL_ACCESS_TOKEN from an already authorized local test session. Optional
expectedModelSettings pins output-token and other model settings; mismatches fail
preflight. Match the user's actual nonsecret model settings in a disposable test
configuration before claiming the baseline represents that application setup.

```powershell
$env:PYTHONUTF8='1'
python -m unittest scripts.ai_dashboard_eval.test_evaluation -v
powershell -ExecutionPolicy Bypass -File scripts/ai-dashboard-conversation-evaluation.ps1 `
  -AccountPath artifacts/ai-conversation-evaluation-account.json `
  -OutputPath artifacts/conversation-baseline -Trials 2 -Workers 2 `
  -ObserveDisposableProvider
```

The default job prepares only. It creates a fresh owned synthetic VEHICLE/source
per scenario/trial (two sources/devices only for declared ambiguity/replacement
profiles), ingests known GPS/number/boolean/text observations through source-token
HTTP ingestion and decommissions its own devices in finally. It never creates a
dashboard, changes preferences or relies on a live HSL feed. Numeric zero, false,
missing door samples, gaps, stale evidence and empty windows are intentional.
History selection GETs the stored ancestor and sends the next refinement from it.
Concurrent branches use the same immutable parent, not a mocked race.

One controlled fault case requires the opt-in observer: a real OpenAI response
is forwarded, then one map setting value is changed from boolean to string so
ordinary dashboard preparation rejects it. This fault is labelled in the report;
other responses are forwarded unchanged. No fake successful response replaces a
required provider call.

### Actual calls and safe local observation

- ObserveDisposableProvider temporarily changes only the dedicated test provider's
  base URL to a local fixed-upstream OpenAI Responses observer. Never use it on a
  shared/user provider or concurrently with another evaluation using that provider.
- The listener is temporary, reachable by local Docker via host.docker.internal,
  accepts only the response endpoint and forwards credentials/bodies in memory.
  It writes only marker/model/status/token counts/fault flags, not keys, messages,
  raw provider responses or private reasoning. It supports Spring chunked HTTP.
- Original provider URL is restored in finally and checked after the run. A forced
  process kill cannot run finally: restore the original URL through the ordinary
  provider API and decommission only devices with that exact run's EVAL marker.
- Extra calls per turn are counted as repair calls; a concurrent two-request turn
  has two initial calls. Token usage is recorded only when returned. Without an
  observer actual counts/usage are unknown, not zero; metadata alone is not
  sufficient for the final observed-live gate.
- Long legitimate provider calls may continue up to the harness's600s network
  ceiling. No new production response timeout or speculative duplicate is added.

### Results and failure semantics

summary.json and incremental trials.json under the specified ignored artifacts
folder distinguish conversation definitions, independent trials, turns and
assertions. They include exact model parameters, source/corpus hashes, first
failing turn, allowed-difference checks, observed calls/repairs/usage and cleanup.
Fixture-only accepted definitions are stored for diagnosis; unrestricted task
context and provider bodies are not. Runtime-source parity is explicitly not
proven automatically by a local source hash.

Exit0 means all selected outcomes passed; exit1 is a recorded failed-quality
baseline, not a reason to alter expected answers. Exit2 means preflight/provider
setup blocked collection. Partial/unreached turns remain in the denominator.
Provider failure is never a successful NEEDS_INPUT result. An already-satisfied
requested property can pass; an unchanged definition cannot satisfy an unsatisfied
move/title/visibility goal merely because the assistant says it is done.

### Separate execution and browser jobs

`ai-dashboard-conversation-execution.py` requires --confirm-fixture-execution.
Its ordinary mode asks the real configured model for one simple current-speed
card, GET-recovers the review, explicitly confirms the same version/digest,
compares the saved private draft exactly and verifies replay returns the same ID.
Only its own draft is archived and its devices decommissioned afterward.

--renderer-reference instead prepares a clearly labelled synthetic reference
through the ordinary prepare API and privately publishes it for renderer checks.
This is not a successful AI output. --keep-for-browser defers its cleanup explicitly;
use --cleanup-handoff with that exact report afterward. Cleanup checks the owned
resource identities and run marker before archiving/decommissioning.

For1100/420 px shared-preview measurements, temporarily copy
scripts/ai_dashboard_eval/preview-reference.html into the frontend Vite root,
replace its dashboard UUID placeholder with the owned reference ID and use the
normal authenticated session. The page imports the existing shared component and
styles; no token is in the template. Choose both harness width and corresponding
preview breakpoint. Remove the temporary HTML after browser inspection. This
measures the actual shared canvas at narrow width, not full native mobile-shell
emulation. Save screenshots, key-element bounds, explicit no-data/stale states and
limitations separately from real-model quality. Real HSL integration is a separate
read-only source/freshness probe; lack of a fresh live bus must not alter synthetic
fixture expectations.

### Final architecture release gate

Baseline collection can be complete with known failed-quality cases. After the
state/capability/operation/verifier/repair changes, run all eight critical sequences
five times each and require all40 trials to fulfill mandatory outcomes with zero
hard preservation/access violations. Across the complete nonambiguous corpus,
require at least95% success, all deterministic security/contract checks and the
separate browser/execution acceptance. Compare alternative configured models using
the same frozen corpus; retain the selected model and record any explicit change.
These gates are engineering thresholds, not statistical guarantees. No missing
provider/stack/browser evidence may be counted as a pass.

### Evaluation isolation and corpus revisions

Planning, execution and cleanup entrypoints take an atomic local account lease.
Do not bypass a live lease or share a test provider with a different account/job.
After a forced kill, verify its recorded PID has stopped, restore the provider
URL through the owner API, clean exact owned fixture markers, then remove only
that stale lease. The lease is local coordination, not a distributed security lock.

The foreign-task case also requires VOLT_EVAL_FOREIGN_ACCESS_TOKEN from another
ordinary authenticated account. The runner verifies a different account scope
and expects404 on clarification of the owner's plan; missing credentials cannot
pass. Decommissioning an owned device does not revoke its historical read access.
The lifecycle case permits a safe rejection or a valid owned historical review.

Corpus revision1 contained24 cases/50 turns. Revision2 adds foreign task isolation
and corrects lifecycle/unsupported-option expectations. Unsupported alternatives
can pass only with the declared compatible result and a visible disclosure.
The declared substring disclosure check is an approximation, not semantic prose
judging. Preserve revision1 reports and its corpus snapshot; never silently rescore
or combine revised outcomes into its pass rate. Record a new corpus hash/revision
and rerun affected scenarios. A model comparison requires the same entire revision.

### Recorded initial evidence (2026-10-23/24)

The complete frozen revision1 run used gpt-5-nano with maxOutputTokens32000,
matching the user's nonsecret settings in a dedicated test configuration.
Its48 trials covered100 turns and observed166 real provider requests. Raw results:
11/48 conversations and36/100 turns passed; only2/24 scenario definitions passed
both repeats. These are diagnostic revision1 scores, not a release acceptance or
a corrected revision2 success rate. Early4096-token interrupted diagnostics are
retained separately and excluded. Production prompts/runtime were unchanged.

Evidence is in artifacts/conversation-baseline-matched-2026-10-23, including its
exact corpus-revision1.json snapshot. All48 trial cleanups completed. The raw
global noDashboardMutation flag is false because a separately labelled renderer
reference was created concurrently on the same test account. This invalidates
that run's inventory isolation claim; it is not evidence that planning created a
dashboard. The new account lease prevents that overlap. A separate clean planning
probe has equal before/after inventories; its unobserved model counts remain null.
The independent observed foreign-task run also preserves dashboard inventories.

Revision2 affected-case follow-up is recorded separately in
artifacts/conversation-revision2-corrections; foreign-account checks are in
artifacts/conversation-foreign-isolation. Do not merge their scores with revision1.
Report categories retain provider/application availability and failed prior-turn
dependencies. A later undo failure following an initial failed creation cannot by
itself establish a standalone undo defect.

The separate real-model execution job passed exact recovered review/draft equality
and idempotent confirmation replay, then archived its private draft and
decommissioned its fixture: artifacts/conversation-model-execution.json.
The shared-renderer reference is explicitly synthetic rather than AI output.
Desktop1100/mobile420 container evidence, compact48px cards, zero/false, stale and
empty-window checks are in artifacts/conversation-browser-baseline. This does not
cover native mobile browser-shell behavior. A remaining visual limitation is the
24h chart's identical clock-only endpoint labels and points clustered at the end;
renderer baseline collection is not an assertion that every visual is satisfactory.

The separate real HSL source was historically readable but stale at baseline time;
it is not advertised as a fresh working bus. Final source freshness is captured in
artifacts/conversation-hsl-integration-current.json. Provider URL/test settings
restoration and owned interrupted-fixture cleanup are recorded in
artifacts/conversation-final-cleanup.json. User model settings were not changed.


## Durable task-state verification

The persist-ai-dashboard-task-state change builds on the recorded evaluation
baseline, without changing that baseline's scores. Run the focused
DashboardWorkingStatePostgresTest, DashboardTaskConversationTest and existing
clarification/preparation tests, then the ai-agent service suite. PostgreSQL
evidence covers failure checkpoint/restart reads, conflicting writers, transactional
request deduplication, branch isolation, expiry, foreign scope and retention.
Frontend conversation/recovery checks cover stable lost-response request IDs,
initial-failure recovery, prior preview preservation and exact history selection.

The revisioned Docker smoke is separate from quality scoring:

```powershell
python scripts/ai-dashboard-task-state-smoke.py `
  --account artifacts/ai-conversation-evaluation-account.json `
  --output artifacts/dashboard-task-state-runtime.json --observe-disposable-provider
```

It checks a real request and exact duplicate, different-payload409, foreign404,
explicit name survival after a controlled provider failure, unchanged last review,
no new model sequence on failed-turn replay, stale-revision409 and initial failure
without an invented preview. Required model quality is graded separately from state
contract checks. Provider/settings restoration and fixture teardown remain mandatory.
Replay selected shared-corpus name/repair cases twice with the same model settings;
retain quality failures rather than weakening expected outcomes. Task summaries are
now retained in sanitized per-turn snapshots for state diagnosis. These focused
runs are not a replacement for the final whole-corpus release gate.

Browser acceptance must reload the owner task, show pending/failed status with
the previous shared preview, disable stale confirmation, select an older exact
review with GET only, then send a continuation based on that selected review.
Do not call an API-only smoke a browser test or a lease-expired recovery a
successful uninterrupted provider response.

The final state smoke additionally answers a real initial naming question while
the disposable provider is deliberately unavailable, then resumes that same branch
with the saved name after connectivity returns. An older question is not an older
accepted preview. Browser acceptance also selects an old review, reloads, recovers
that exact selection, and edits from its snapshot; check the parent branch remains
unchanged. Recovery navigation uses GET only and does not execute a dashboard.
## Capability resolution verification

The shared descriptor already supported recorded numeric/boolean line history,
text list history, compact scalar cards and gap preservation. This change joins
that descriptor to actual authorized metadata before model planning. The owning
code is DashboardCapabilityResolution plus DashboardCapabilityEvidence; AI renews
opaque data refs, and WidgetConfiguration/VehicleFieldSelection use the same read
contract. No alternate compatibility matrix or fabricated numeric door metric is
introduced. Model-side metrics/field hints are projections of that response rather
than independent telemetry catalogue reads.

Run `python scripts/ai-dashboard-capability-smoke.py --account <ignored-account>
--output artifacts/dashboard-capabilities-runtime.json` with normal transient auth.
This contract run invokes no model and creates no dashboards. It uses measured
normal/ambiguous/stale fixtures, checks false versus missing, declared text list
constraints, source identity and foreign404, and decommissions its fixtures.
Run real-model dialogue comparisons separately with the shared harness; preserve
failed drafts and score corrections as separate corpus revisions. A passing
NEEDS_INPUT status alone is insufficient evidence that a source answer can resume
work: additionally answer an actual source question, inject provider failure and
verify the selected source survives before continuing. Keep a live HSL read separate
from deterministic fixture evidence, with its own asOf and freshness.
Run `python scripts/ai-dashboard-source-choice-smoke.py --account <ignored-account>
--output artifacts/dashboard-capabilities-source-choice-model.json
--observe-disposable-provider` after the planning inventory lease is released.
The job derives the displayed choice label from scoped server mappings, answers
it while the provider is unavailable, restores connectivity and verifies the exact
selected external source in the prepared definition. Never treat a plain question
or NEEDS_INPUT alone as successful continuation. Keep browser fixtures private,
archive test dashboards and decommission sources after capturing evidence.

Capability checkpoint 2026-10-24: Docker contract30/30; final selected real-model
corpus11/16 trials and20/28 turns, quality FAILED. The unchanged three-case
comparison is baseline5/6, durable-state4/6, capability-stage4/6. Full percentages
from corpus revisions1 and2 are not directly comparable. Evidence is retained in
ignored artifacts/dashboard-capabilities-report.md, model-final/, comparison.json
and browser screenshots. Never rewrite these failures after a local fix.
The ordinary editor and real model definition were checked through the shared
renderer. Small editor panels may clip under editing headers; an owned device
outside the first50 selector choices may show Unavailable selection until search
or pagination. These existing UI limits do not establish failure of source data,
but prevent claiming full compact-layout acceptance. Semantic no-op/lost-role
failures remain stage4/5 work; malformed model response and overly broad provider
failure classification remain stage6 work. Capability-stage completion is not
whole-program quality acceptance or default release enable.

Capability-stage source-choice follow-up: first run FAILED (options omitted and
malformed continuation); after the server option-list correction, the new focused
run passed9/9 state/source checks and6/6 simple-definition checks. Exact selected
source survives an intentional provider outage before retry. Both attempts are
retained separately in dashboard-capabilities-source-choice-model*.json; do not
replace the failed corpus11/16 result with this focused success. Stage3 archived
as2026-10-24-resolve-ai-dashboard-widget-capabilities; final release gate remains
unsatisfied. Temporary provider URL and original4096-token profile were restored.


## Semantic operation compiler: stage4 evidence, 2026-10-24

The opt-in dashboard-operations-v1 compiler is implemented. Default enable is
false: the measured quality gate is FAILED and stages5/6 remain required.
Frozen real-model gpt-5-nano results: 26/50 dialogues,75/104 turns (main25/48,
71/98; injected fault1/2,4/6). Exact unchanged20-case stage1 comparison improves
10/40 to21/40 dialogues. The same8-case stage3 selection improves11/16 to12/16
and20/28 to22/28 turns. Names/recovery across stages1/2/3/4:5/6,4/6,4/6,5/6.
Fault requests/assertions remain unchanged but injection now targets the typed
compiler boundary. Different accounts/corpus revisions and only two repetitions
limit broader conclusions; this is not the final five-trial release gate.

Remaining failures include omitted roles, duplicates, collateral edits, group
placement and recovery losing speed/history. The frozen run retains two foreign
actor setup409 failures; a provisioned-account rerun passed2/2 dialogues,4/4 turns
with expected404. Original scores were not replaced. A post-corpus name-checkpoint
fix reproduced two regressions then passed30 related tests. Its separate live
probe passed6/7 assertions: rename persisted, but a subsequent header-only edit
changed other fields. The full corpus was not rerun after that fix.

Docker Java checks:121 AI,93 dashboard,10 shared passed; later schema/name changes
passed17/30 focused tests. Frontend389, typecheck/build and harness35 passed.
Initial Testcontainers socket errors and frontend parallel timeouts are preserved
alongside successful corrected runs. Real confirmed private creation passed9/9,
including exact review equality and idempotent replay. Mobile browser inspection
found a one-row value clipped by the editor heading; visual acceptance is pending.

Local primary evidence (ignored artifacts): dashboard-operations-report.md,
dashboard-operations-comparison.json, dashboard-operations-model-final/summary.json,
dashboard-operations-fault-model/summary.json, dashboard-operations-foreign-ready/summary.json,
dashboard-operations-name-model.json, dashboard-operations-execution-schema.json,
dashboard-operations-browser.json, dashboard-operations-rollback.json and
dashboard-operations-execution-cleanup.json, all under artifacts/.
Main/fault runs did not mutate dashboards and cleaned source fixtures. The isolated
fault provider was restored/deleted. QA plans retain normal retention; no admin
account-deletion bypass was used. Existing AGENTS.md/workflows remain applicable.


## Stage4 corrective follow-up, 2026-10-25

fix-ai-dashboard-operation-contract corrects keep_review (exclusive exact payload,
current authorization, no reflow, empty mask, pending dispositions unchanged),
same-turn @id widget titles, authorized replacement scope (including inherited
legacy bindings), distinct history view reuse, and strict schema ranges/enums.
Missing legacy vehicle-timeline visualization means list, not line. Legacy reviews
remain executable; generation defaults off and stages5/6 remain required.

Frozen corrective sample: four preselected unchanged corpus cases, two trials each,
passed5/8 dialogues and13/16 turns versus0/8 and7/16 before correction. This does not
rescore or replace the earlier full26/50,75/104 result. Final targeted smoke passed
4/6 dialogues,28/32 checks: title/keep2/2, single-card replacement2/2, separate history
list0/2. Intermediate smoke2/6 and startup-only dependency failure are retained.
Its checker was corrected to read presentation.visualization and accept supported
time-series history; original failed trials were not rescored.

The sample retained two HTTP400 compound replacements before model invocation.
Opt-in context then dropped unused templates and duplicate per-device metrics/fields
before the unchanged input budget. Current data choices, base widgets and credential
scanning remain intact. The separate replacement rerun is recorded under
artifacts/dashboard-operation-fixes-context-model/summary.json and
artifacts/dashboard-operation-fixes-context-diagnostics.json.
It is supplemental, not a replacement of the5/8 sample. A remaining partial model
replacement retained the old map while switching other widgets; whole-goal checking
and repair remain later-stage work. Selected sample preceded this context correction
and the final legacy default refinement; source fingerprints record each run.

Docker full initial checks:129 AI,93 dashboard,10 shared passed. After the live keep
correction,37 focused AI checks passed; after legacy-default correction, the complete
AI suite passed131. Context compaction and enabled/disabled projection passed31
focused checks. Harness35 passed. Structure/strict OpenSpec are checked again at
closure. No frontend code/response DTO contract changed; prior389 frontend checks
are historical evidence, not a new corrective run. Synthetic fixture telemetry is
fresh and independent of night-time bus availability. All jobs are PLAN_ONLY,
fixtures are decommissioned and the provider config is unchanged. QA plans use
normal retention. Existing AGENTS.md and skill workflows remain applicable.

Primary ignored artifacts: dashboard-operation-fixes-report.md,
dashboard-operation-fixes-comparison.json, dashboard-operation-fixes-java.log,
dashboard-operation-fixes-ai-final.log, dashboard-operation-fixes-context-tests.log,
dashboard-operation-fixes-smoke-final/summary.json,
dashboard-operation-fixes-corpus/summary.json and dashboard-operation-fixes-rollback.json
under artifacts/.

Supplement after compaction:0/2 dialogues,2/4 turns; both requests reached model
processing without HTTP errors and cleaned their fixtures. One candidate preserved
widget IDs/layout but retained the old map device; the other was not READY. The
revision2 replacement predicate also over-restricts necessary query.series binding
changes and lacks an explicit every-widget vehicleB check. Historical scores are
unchanged; artifacts/dashboard-operation-fixes-binding-audit.json independently
checks the replacement device and preserves the failed outcome. Stage5 handoff
records the predicate correction; no retrospective reclassification is used.

## A5 stabilization and failed diagnostic evidence — 2026-10-28

A5 tasks2.1–2.2 have focused offline evidence; task2.3 is not accepted. The two
bounded development reports are `artifacts/a5-first-pass-20261028-01/summary.json`
and `artifacts/a5-first-pass-20261028-02/summary.json`. Each keeps nine declared
turns and four dialogues, including failures/unreached turns. Checkpoint01 is0/9
with a preparation failure and a harness accounting halt. The unrelated-context
truncation accounting defect was fixed offline; the old report is immutable.
Checkpoint02 links that report, is explicitly statistically non-independent,
shares its remaining call/token/money/time ceiling, and also remains0/9.
Two reached turns failed unknown/forward widget selection and planner contract
validation; seven remaining steps stopped at the predeclared campaign time gate.
Neither report is a successful quality claim, an independent retry trial or A6.
The historical live-12 score remains1/5 turns and0/2 dialogues.

`artifacts/a5-stabilization-20261028/usage-ledger-evidence.json` supplements the
reports with scoped exact-turn A2 ledger usage:5 calls,46,422 input tokens
(including1,664 cached),22,111 output tokens (including19,840 reasoning), zero
automatic corrections. Reasoning counts are numerical usage, never private text;
current trace redaction suppresses that field, so the durable ledger supplies it.
USD0.50 is the reserved/charged upper bound, not measured monetary cost. Without
a price snapshot actual cost remains unknown; no economical-model comparison is
claimed. The account has only one actual provider/model and no stronger reference.

Backend evidence: core31 and catalogue/protocol7 focused checks;17 AI plus11
owning dashboard/PostgreSQL focused checks; earlier full AI188 passed. A later
full attempt hit two Docker discovery errors and was followed by a successful
explicit-endpoint integration recheck. The unrestricted frontend run had an
unrelated ML-page timeout; all412 tests passed with maxWorkers=2. Typecheck/build,
harness44, structure and strict OpenSpec86/86 passed. No browser acceptance is
claimed for this increment, and no new dashboard was executed. Synthetic fixtures
were decommissioned through ordinary APIs; history and persistent data remain.

The local diagnostic profile was restored to STANDARD with the previous local
v2 override retained, model/provider settings unchanged and deployment defaults
untouched; see `rollback-profile.json` beside the ledger evidence. This returns
the prior correction behavior, not a newly qualified release policy. A5 core
workflow/first-pass completion remains ahead of final V/R and A6. No A3/V/R/C3/
preview acceptance is closed by these partial slices or failed measurements.

## Docker embedding comparison, 2026-10-29 (reported 2026-10-30)

The user explicitly authorized real-model Docker verification and a matched
embeddings OFF/ON replay. All 27 frozen cases were declared once per arm with
STANDARD and the existing gpt-5-nano configuration. OFF passed 36/54 turns and
13/27 complete scenarios; ON passed 37/54 and 15/27. Both include three blocked
fault-provider turns. The earlier full Spring AI result remains 39/54 and 16/27.
Ten historical campaigns are compared on matching cases with profile, grader,
corpus, model-setting and driver caveats. One trial and fixed order do not establish
causal improvement; quality gates remain open and earlier reports are immutable.

ON has 44/44 HYBRID retrieval events and 45 real local embedding requests without
fallback. OFF has no embedding requests. Chat calls were 102 and 101; a separately
preserved aborted infrastructure run accounts for nine more calls. During startup,
the full owner catalogue exceeded the client-input guard budget. A separately
bounded and secret-scanned owner-catalogue path fixed this without weakening
client limits; 354 Java tests passed and the runtime guard class hash matched.

Both arms preserved dashboard inventory and cleaned their disposable devices.
Postflight restored the original active conversation, retained chat settings,
released the account lease and returned embeddings to OFF. All 12 application
HTTP endpoints passed health/UI checks; HSL was resumed healthy. Browser smoke
covered the existing dashboard, inventory and lazy-loaded recorded charts.
Deterministic history/trace fixture checks are separate from real-model quality.
No training or frozen-corpus/grader modification was performed.

Detailed results and per-case evidence: artifacts/widget-ab-20261029-r3/report-ru.md,
comparison.md, comparison.json, off/summary.json, on/summary.json and postflight.json.
The primary remaining failures are exact edit semantics, invalid proposals and
CONTEXT_INSUFFICIENT during selected-vehicle replacement. Embedding retrieval
alone did not resolve them; no default enable was justified by this trial.
