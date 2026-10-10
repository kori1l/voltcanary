# Console interface workflows

The implemented console follows [the interface audit](../../target/ui-design-requirements.md). All nine associated OpenSpec changes were completed and archived on 2026-10-07. Their acceptance checklists and verification evidence are retained in the archive; 45 requirements were promoted into nine main specifications.

## Shell and evidence

The responsive shell has a mobile navigation toggle, a main-content skip link and one current destination. Native dialogs contain focus, support Escape, restore opening focus and guard changed drafts. Destructive actions name the affected object and impact; cancellation performs no request.

Dashboard resources load independently. A failed resource displays unavailable evidence, or retained evidence with its named retrieval time and a stale warning. Idle successful sections do not repeat refresh timestamp rows; active refresh and stale evidence retain contextual timestamps. Successful empty responses may show zero; failed responses may not. Registered active devices and recent telemetry are separate counts. Inventory and alert cohorts are explicitly bounded by the request limits. ML monitoring reports fallback-enabled policies with a denominator rather than implying a measured runtime fallback frequency; latency remains unavailable where the API provides none.

## Identity and administration

Login, registration, reset and verification share labeled task forms. Password visibility is explicit. Incoming `token` query parameters are captured in page memory and removed using history replacement; manual token entry remains available. Recovery responses describe eligible accounts and configured delivery without exposing whether an account exists. A successful registration followed by failed login can retry login without repeating registration.

Personal profile, password change and all-session sign-out live in Settings. `/account` remains the administration route. USER and SUPPORT do not load administration lists. ADMIN edits role/status as a draft, then applies it through a named confirmation. User session inspection displays metadata and never refresh-token values. Revoking refresh sessions does not promise immediate invalidation of access JWTs.

## Device setup and imports

Inventory opens with recognizable device icon tiles grouped by location; the compact table remains an optional comparison view. Device registration is distinguished from the last measurement timestamp. The four-step wizard validates each advance, explicitly chooses existing versus new location, and reports failures within the dialog.

After the server returns a location/device/source ID, a retry resumes recorded steps instead of recreating them. Simulator credentials remain in memory; a failed start reuses the saved configuration. Closing and reopening a partially completed wizard retains its in-memory continuation and returns to Review. Reloading the page loses that continuation. A transport failure before an ID is returned cannot establish whether the server committed creation: check inventory before starting a new setup. This is not a server idempotency guarantee.

Device and source CSV imports accept files, offer templates and show numbered local previews. The payload remains the existing plain-text CSV contract. The current server uses comma splitting and does not support quoted embedded commas; the preview rejects that syntax. Server responses can indicate partial success. Correct rejected rows before another import rather than resending already imported rows. References and authorization are still validated by the server.

Source credential rotation/revocation and disable actions explain sender impact before mutation. Analytics exposes baseline-window days while preserving other JSON keys. Simulator presets load as drafts; saved configuration and runtime status are shown separately. Starting HTTP simulation explicitly explains its existing credential rotation behavior.

## Telemetry and alert rules

Overview presents registration, measurement freshness and risk evidence with links to task-specific tabs. Telemetry preserves unavailable selections and reconciles displayed versus available counts. Exact Custom intervals use local timezone labels and second precision; metric analysis provides date fields as a keyboard alternative to brush selection. Charts preserve measured zero and separate explicit missing samples and long sampling gaps. Related-signal explanations are inferred evidence, not confirmed diagnoses.

Alert rule editing uses canonical metric/scope/channel IDs with readable labels, condition-specific parameters and a readable if/then summary. Test uses an explicit interval and reports a separate result; it does not create a rule. Editing preserves existing configuration, cooldown and deduplication fields. Resolution requires a reviewed reason. Notification setup distinguishes configured, enabled, adapter availability and actual test-delivery status. Quiet-hour preferences display their timezone, routing selections and critical bypass; saved unsupported channel choices are preserved with an explanation.

## Maintenance

Timeline keeps device labels visible inside its own horizontal scrolling region. Exact start/end timestamps accompany short windows; minimum-width visual markers do not imply a longer duration. Agenda is the default mobile view. Fleet and device scheduling share named policy fields: alert suppression, analytics exclusion and training exclusion remain independent flags. Invalid or reversed intervals fail locally. Cancel and early finish explain their downstream impact before submission.

Outcome recording separates observed result, actual time, notes and failure-specific evidence. Normal inspections clear hidden failure fields. Existing outcomes are shown with server timestamps and canonical operation/window/device links; operator evidence is not presented as an automated diagnosis.

## ML workspace

Settings, datasets, import, labels, training, models, policies and monitoring are separate tabs. Settings must load the selected account/device scope before saving. Responses for older scopes and dataset selections cannot replace the current selection. Pipeline prerequisites link to remediation and distinguish anomaly detection from horizon-dependent failure prediction.

Dataset preparation still requires a matching server preview before commit. Existing stale-preview and request-order guards remain in place. Import provides a bounded CSV/JSONL sample and visual column mapping while sending the original file through the existing contract. Dataset detail presents validation and lineage; snapshots and training require compatible family/horizon inputs. Label corrections require a reason and retain audit history. Generation shows the actual job scope and allows status refresh without another start.

Model decisions and policy changes are reviewed with named scope, serving mode, fallback and inheritance. Delete keeps the server lifecycle-impact gates. Monitoring shows server configuration/readiness evidence; missing runtime measurements remain unavailable. UI-only changes do not modify training, inference, artifact or deployment contracts and therefore do not trigger the ML production Docker smoke baseline.

## Assistant and providers

Requests summarize allowlisted device, metric and interval context. Advanced JSON rejects credential fields. Editing the request/context/provider/model invalidates its previous plan. Plans show steps, affected objects and readable parameters; read-only execution and reviewed mutating execution have one primary command. Recorded execution results prevent duplicate execution and display per-action failures.

Provider forms follow advertised capabilities, including authentication, tool support and JSON mode. Account-default selection remains server resolved. Credential replacement uses a separate in-memory password field and reviewed impact; returned metadata remains masked. Providers with authentication NONE require no credential. Provider/model removal names the affected configuration.

Alert assignee information remains read-only because the current API exposes no assignment mutation. The console explains this limitation rather than offering a non-functional assignment control.

## Verification evidence

Local browser fixtures render actual route components without restarting the stopped backend. They exercise responsive layout and UI state handling, not live authorization or delivery. Tests cover resource failures and stale responses, focus/draft behavior, canonical IDs, authentication drafts, role visibility, resumed creation, chart gaps and incident lifecycle. Generated browser evidence stays under ignored `artifacts/` directories. Required final checks and remaining browser coverage are tracked in each change's `tasks.md`.

### Validation on 2026-10-06

- Frontend: TypeScript passes; 27 test files / 77 tests pass; production build passes. The existing bundle-size advisory remains (approximately 645 kB before gzip).
- OpenSpec: 26 strict validation items pass. Repository structure passes; reported risks are existing backend/Python sizes and constructor dependencies. The updated route pages no longer trigger the oversized frontend-page warning.
- Browser: populated primary routes checked at 360, 768 and 1440 pixels; all eight ML tabs and seven device tabs checked at those widths. Error/loading/empty resources checked on nine operational routes, with retained-stale dashboard evidence after refresh failure.
- Enlarged presentation: 17 routes checked with authored 200% page scaling and separately doubled computed text sizes. These are fixture simulations, not a claim of native browser text-only zoom certification. No document-level horizontal overflow was observed after the fixes. Tables and Timeline keep intentional scrolling inside named regions.
- Dialogs: mobile scheduling draft, Keep/Discard, Escape/focus restoration, onboarding steps, sessions, outcomes and reviewed label/policy actions inspected. Canonical payloads, cancellation without mutation, secret non-disclosure, late-response rejection, duplicate job prevention and exact second-level keyboard analysis have focused tests.
- Screenshots: ignored `artifacts/console-ui-verification/dashboard-desktop.png`, `maintenance-desktop.png` and `onboarding-mobile.png`.
- Infrastructure stayed stopped; only a temporary frontend server was used for local fixtures and stopped after verification. Live authentication, notification delivery, inference/training and capacity claims require their existing integration verification guides. No runtime/API changes were introduced by this console implementation.

| Change | Implemented coverage | Focused evidence |
| --- | --- | --- |
| establish-console-design-system | Shell, controls, responsive regions, dialogs, selectors, timestamps | AppLayout/Dialog tests; five reference screens; width and enlargement checks |
| fix-console-data-state-semantics | Independent resources, unknown/empty/stale evidence and retry | Resource/Dashboard tests; failure and retained-stale browser states |
| improve-account-auth-console | Auth, recovery, personal security, reviewed administration/sessions | Auth/Admin/Sessions tests; populated account and responsive auth forms |
| improve-device-onboarding-configuration | Inventory, wizard recovery, CSV preview, registry/configuration and simulator | Partial-create/CSV tests; four mobile wizard steps and configuration/simulation tabs |
| improve-device-telemetry-analysis | Overview, exact ranges, selected metrics, gaps, analysis and calculation status | Range/chart/job/keyboard tests; populated deep link and metric dialog |
| improve-alert-notification-console | Inbox, resolution, condition-aware rules, channels and quiet hours | Incident/rule/channel/preferences tests; populated delivery and Email/Telegram forms |
| improve-maintenance-console | Timeline/Agenda, scheduling/impact, cancellation and outcomes | Policy/outcome tests; scheduling guard, short-window view and outcome history |
| improve-ml-console-experience | Pipeline, scope, datasets/import, labels/generation, training, models/policies and monitoring | ML workflow/import/training/scope tests; populated dataset lineage, label and policy review |
| improve-ai-assistant-console | Context, plan review/execution, providers/models and credential rotation | Plan/context/late-response/secret tests; populated provider and read-only plan |

No AGENTS.md or skill update is required: repository architecture, validation commands and agent workflow remain unchanged.

### Scoped evidence transitions (2026-10-08)

Implemented change: `fix-console-evidence-state-transitions`. Shared
`useSectionResource` and `useEntityResources` own retained evidence and request
generations. Inventory scope includes search, location and registration status;
telemetry and risk have independent resources keyed by canonical device ID.
Notification attempts have a resource and local retry per alert ID.

| Trigger | Result | Retained evidence |
| --- | --- | --- |
| No read requested | `idle` | None |
| Waiting for inventory | `waiting-dependency` | None |
| Inventory fails terminally | `error`, actionable inventory retry | None for dependent signals |
| First read starts | `loading` | None |
| Same-scope refresh starts | Previous state, `refreshing=true` | Same-scope evidence only |
| Successful nonempty read | `ready` | New result and UTC `receivedAt` |
| Successful empty read | `empty` | Authoritative empty result |
| Transport failure without evidence | `error` | None; no assertion of absence |
| Transport failure after success | `stale` | Prior data and original timestamp |
| HTTP 401/403 | `no-access` | Data and retrieval timestamp cleared |
| Scope switch | New scope starts `idle` | Prior scope invisible |
| Superseded or obsolete-scope response | Ignored | Current scope unchanged |
| Local retry succeeds | `ready` or `empty` | Error cleared, timestamp replaced |

ML inventory failures appear as unavailable prerequisites; retry targets the
specific datasets, snapshots, runs, models or policies loader. Equal transport
errors share one message while retaining named section state and local buttons.
Empty-array presentation fallbacks do not establish pipeline absence. Selected
lineage readiness remains owned by `fix-ml-pipeline-lineage-readiness`.

Settings/profile and routing errors finish loading. Alert history reports no
attempts only following a successful empty read. Device risk/telemetry show
their own retrieval age and stale feedback; server measurement/calculation
timestamps retain their semantics. Clock ticks update ages and Dashboard/Overview
freshness without fetching.

Devices, Dashboard, Alerts and ML inventory offer Off/30 seconds/1 minute/5 minutes
read refresh. Default is Off. Scheduling adds jitter and bounded exponential
backoff, prevents overlapping scheduled reads, pauses hidden views and checks
for authentication before reading after resume. Scope change/unmount cancels
the timer; resource generations reject obsolete responses. Polling does not
submit calculations, training, notification tests, source-token rotations or
other mutations.

Focused tests and read-only synthetic fixtures render actual route components
under StrictMode. Devices, ML and alert attempts were exercised at 390/768/1440:
retained risk has local stale feedback, ML failures give “Unable to verify”,
and attempts retry changes error into authoritative empty. Initial fixture
overflow came from a missing console stylesheet; corrected fixtures and live
views fit at all three widths. Live acceptance used an authenticated local
session, 20 existing demo devices and six alerts. Controlled read failures were
injected by an ignored read-only harness while successful reads went to the real
API; backend services and records were unchanged. Live retained risk, ML unknown
inventories, per-alert history error/retry and authoritative empty history were
verified at 390/768/1440. A prior unauthenticated read showed terminal HTTP 401
feedback. These checks establish UI/API integration, not actual notification
delivery, training/inference, capacity or a security audit.

Validation: frontend typecheck, 33 test files / 92 tests and production build
pass; OpenSpec strict validation passes 42 items; repository structure passes
with existing backend/Python warnings. The existing bundle-size advisory remains.

### Selected ML lineage verification (2026-10-08)

`fix-ml-pipeline-lineage-readiness` uses one visible account/device, dataset,
family/horizon, snapshot, run, model and policy context. Dataset/snapshot defaults
are visible; subsequent selections and remediation tabs retain the context.
Labels use a dataset-keyed resource with scope/late-response guards. Positive
failure labels must match the selected owner, dataset, horizon and optional
device. Anomaly detection does not require failure labels/horizon.

`pipelineContext.ts` owns the compatibility matrix. Snapshot artifacts and known
lifecycle, successful run artifacts and completion time, canonical model training
lineage, owner/type compatibility and server approval evidence are required for
ready. Legacy missing owner/family/lifecycle/run metadata remains review/unknown.
QUEUED/RUNNING is progress; disabled policy blocks configuration while leaving
matching model approval visible. SHADOW/CANARY/ACTIVE is configuration evidence.
Monitoring requires an actual matching device/model/family/horizon record and
displays its timestamp; it never derives runtime success from approval/policy.

Backend read responses now expose persisted dataset accountId and model
trainingRunId. No database change, training/inference algorithm, artifact format
or approval/deployment guard changes were made. Analytics Maven tests, including
PostgreSQL integration and JSON lineage serialization, pass (136 service tests
and two common-events tests). Local analytics was restarted and returned health
UP. Browser fixtures use actual components and full console styles: both modes,
RUNNING, disabled policy, foreign dataset/14-versus-7-day mismatch, compatible
failure lineage and unavailable inventory recovery with preserved selections
were exercised. Responsive views fit at 390/768/1440. This proves console
behavior, not newly executed training, real serving or notification delivery.

Frontend final gates: typecheck, 35 test files / 107 tests and production build
pass. Strict OpenSpec validation passes 41 items before archival; structure
passes with the existing warnings. No AGENTS.md/skill update is needed.

### Device console regression verification on 2026-10-07

Follow-up change: `fix-device-console-regressions`. Restored tiles as the initial inventory view and retained the table and filters. Corrected appliance icon matching so WASHING_MACHINE does not match AC through the substring MACHINE; added a kettle silhouette.

Reproduced the false device-context error under React StrictMode before the fix. Device load feedback now uses the current resource state, exposes the actual API error and a Retry action, and clears on recovery. Location lookup failure remains separate and does not label a configured location as absent. Healthy section timestamps are suppressed; named stale evidence remains visible.

Validation: 30 frontend test files / 84 tests pass; typecheck and production build pass (existing bundle-size advisory). Browser fixtures run actual components under StrictMode: devices, Overview and Analytics checked at 360/768/1280 pixels with no document overflow, no false device error and no idle refresh rows. True API failure retains Retry; stale refresh retains the device card and named retrieval evidence. Screenshot: ignored `artifacts/device-console-repair/device-tiles.png`. These fixtures do not establish live backend availability.

### Navigation context and session recovery (2026-10-08)

Implemented change: `improve-console-context-and-session-recovery`. ML tabs,
account/device scope and selected lineage; Devices filters/view; Alerts filters,
incident and view; and Maintenance range/view/operation use route-specific typed
query allowlists. One UI action merges its URL patches into one history entry.
Back/reload restores task context. Search edits replace the current history entry.
The safe return destination accepts known relative console routes, removes unknown
query fields and rejects external destinations, encoded backslashes and auth loops.

API reads share one token refresh across simultaneous 401s. Definitive refresh
rejection expires the session once and routes to Login with the safe task URL.
Temporary network/5xx refresh failure preserves the session and reports retryable
unavailability. In-flight refreshes, reads and mutation retries cannot cross a new
login identity. Protected UI and resource scopes discard old-account evidence.

The production router guards route/tab/scope changes and browser Back. Edited
forms and recovered drafts offer Keep editing/Discard changes; unload uses the
native browser warning. Discard clears the scoped persisted workflow draft as well
as the visible form. Successful channel/policy/rule/preference/training saves reset
form evidence. Dialog's existing local guard remains intact.

Recoverable training, workbench, alert-rule and notification drafts contain only
explicitly allowed nonsecret fields under user/account/entity/base-revision keys.
Corrupt or incompatible records fall back to defaults; legacy private drafts are
migrated only with proven owner identity. Credentials and arbitrary nested config
stay out of draft storage, route context and assistant task payloads. Existing
access/refresh tokens remain in their dedicated authentication storage contract;
this change does not introduce a new credential-storage design. Volatile generic
form comparison stays in memory. Horizon job recovery stores bounded job references
and recorded state, then reads authoritative status again.

Assistant context previews readable selected device/dataset/incident/operation/
location names, known IDs, filters, ML family/horizon/scope and evidence intervals.
Context changes invalidate previous plans; default prompts follow the task without
overwriting an edited request. Context conveys evidence, not authorization.

Live local verification used the admin test session and existing demo inventory.
A test-only invalid access token with the valid refresh recovered automatically.
An invalid refresh produced Login with the ML Training/account/failure/7-day deep
link, and sign-in restored that exact context. Keyboard Enter exercised guarded
navigation, Keep retained the edited value, and Discard continued to Datasets.
No training, deployment or notification delivery was executed. Automated tests
cover concurrent 401s, transient failure, account switching, stale successful
responses, URL rejection/Back/reload, corrupt storage and persistent Discard.

Final gates: frontend typecheck, 41 test files / 132 tests and production build
pass. Strict OpenSpec validation passes 40 items before archive; structure passes
with existing warnings. The existing bundle-size advisory remains for the later
page-delivery change. No AGENTS.md or project skill update is required.

### Command outcomes and recovery guidance (2026-10-08)

Implemented change: `improve-console-command-feedback`. Focused command state
tracks reviewing/submitting/committed/unknown-result/failed, with synchronous
entity/action locks. Alert Ack/Resolve, rule and channel toggles/deletes, model
Approve/Reject/Archive, training Cancel/Retry and ML lifecycle previews preserve
independent row access and the existing reason/impact/cancellation contracts.
Canonical responses are recorded before read refresh; refreshed lists cannot turn
the acknowledged command into a failed mutation. Read retry never repeats its write.

Channel/rule/dataset/import/snapshot/training/manual-label/policy creation retains
an unknown outcome after a lost transport response. The same create is blocked
until canonical records are read and the operator explicitly reviews a matching
record. Name/lineage matching is a candidate list, not proof of request identity.
These command checkpoints are local to the mounted workflow; the server still
owns canonical records and lifecycle constraints. Delivery tests record the
attempt result separately from diagnostics and expose the recorded attempt for
manual reconciliation. They do not claim provider delivery succeeded.

Typed API errors retain HTTP status, safe code and allowlisted validation fields.
Guidance distinguishes session changes, permissions, invalid fields, dependency
conflicts, rate limits and service/network failures. Provider addresses and
credential-bearing text are removed; arbitrary non-JSON/raw error bodies are not
rendered. Diagnostics codes use a disclosure. Recovery copy does not disclose
account existence or assert email delivery; it describes configured delivery and
approved manual token fallback. Clipboard rejection is announced, leaves a
selectable canonical ID, and sends no unhandled promise.

Dataset/training forms wait for loaded account context, so late initial ownership
cannot reset edited fields. Training horizon URL updates occur on blur after a
valid edit, preserving numeric clearing/typing. This keeps selected lineage
validation and existing snapshot/horizon submission guards intact.

Verification: full frontend 46 files / 142 tests pass, with focused follow-up
checks of ML workflows, clipboard, recovery, typed errors and channels. Typecheck
and production build pass. Strict OpenSpec passes 39 items before archival and
structure passes with existing warnings; the bundle-size advisory remains for
page delivery. No backend contract/migration changed, and AGENTS.md/skills remain
applicable.

Live local admin verification created one disabled disposable channel named
`Local command verification 2026-10-08`, canonical ID
`8eb9098b-4a2c-4854-a9db-4bbadb801060`, using an example.invalid destination and no
credential reference. The UI confirmed its ID/Disabled state. No test send or
notification delivery was executed. Browser fixtures use actual channel/model
components with synthetic responses: keyboard creation with failed list refresh,
read-only retry, approval with reason and retained APPROVED result despite failed
refresh. Views fit 390/768/1440 widths. ML fixtures prove interface behavior, not
actual model training/promotion or serving; the local account had no model records.
Automated integration verifies double Ack and independent alert/channel rows,
ambiguous creation and explicit canonical reconciliation, saved-ID retention after
failed reload, sanitizer boundaries and clipboard failure/success.

### Fleet browsing and first measurement (2026-10-08)

Implemented change: `improve-fleet-browsing-and-device-activation` (R06/R07/R08/R18).
Devices, Maintenance and Alerts expose Previous/Next and loaded-page counts.
Devices/Locations/Maintenance use array overfetch and unknown totals; Alerts
reuse their existing authoritative page response. Device/location selectors
search and continue beyond the initial limits; selected names remain pinned
within their authorized actor scope. Registration status choices come from
ACTIVE/DISABLED/OFFLINE/DECOMMISSIONED, including empty results. Clear filters
resets page context. Search waits 300ms; superseded reads are aborted and scoped
responses cannot replace new selection. References load independently. Inventory
telemetry/risk uses at most six simultaneous network reads, including queued
pending rows, and aborts obsolete work.

Successful device/source creation stays open with activation instructions. API
senders see the ingestion endpoint and a source-token placeholder; MQTT senders
see the configured source topic and administrator-provided broker/auth next step.
Reviewed credential actions explain ingestion access and replacement impact.
Plaintext tokens do not enter URLs or persisted setup recovery. Simulator setup
requires an explicit impact confirmation before issuing its credential. Reload
uses authorized canonical IDs/configuration/run; it never silently rotates a
credential. A lost create response blocks another write until a canonical record
is explicitly reviewed. A definitive refusal allows source correction while
retaining the created device. Failed recovery reads block further setup writes.

Registration/source configuration, waiting for a reading and simulator run state
remain separate. First measurement reads run at most once per five seconds for
one minute, pause while hidden, time out after ten seconds, and never create or
start anything. A zero-valued reading counts as observed. Latest telemetry is
device scoped, so it cannot prove the selected source supplied the sample.
The activation URL survives reload and session return.

Verification: frontend full suite passed 52 files / 158 tests with two workers,
including reconciliation after reload when Review already has no error. Typecheck/build pass.
Focused Maven device/alert reactor passes, including real PostgreSQL fixtures
with 120 devices and 225 operations, stable ties, offset 200, membership-before-
paging and cross-owner exclusion. Browser actual-component fixtures reached
device 101/operation 201, restored pending source IDs after reload, explicitly
reviewed a source, resumed with zero device/source creates, and changed waiting
to measured from a zero reading. Activation fit 390/768/1440 viewport overrides.
Synthetic fixtures prove UI behavior; the live local Demo Refrigerator 01 activation view and reload showed the
existing dated measurement, without claiming current source connectivity. No real source token was issued/rotated by browser acceptance.
Strict OpenSpec passes 38 items before archive; structure checks pass with
existing warnings. Backend tests do not establish fleet capacity. Existing bundle-size advisory
remains for page delivery. AGENTS.md/project skills remain applicable.

### Historical telemetry and readable evidence (2026-10-08)

Implemented change: `improve-telemetry-history-and-readable-evidence` (R19/R20/R23).
Interval-aware metric discovery queries persisted supported numeric names through
optional from/to on `/metrics`. Latest has an independent resource: a failed or
missing latest reading cannot remove readable history or fabricate a current
value for a historical metric. Superseded history is aborted and scoped; parent
workflows resuming after reference reads use the current interval, including
restored preferences. Per-metric history reads remain bounded to six concurrent
requests.

An additive `/readings/envelope` returns first/last and min/max evidence per bounded
time bucket, full-range SQL statistics and persisted quality counts. The console
uses its complete statistics for KPIs, never sampled display values. Original
chart/average interval and raw export contracts remain available. Source/display
counts, mode and gap evidence are textual. Envelope bars preserve extrema while
explicitly leaving within-bucket order and exact threshold transitions unknown.
The pure reducer for raw source curves preserves endpoints, multiple bucket peaks
and troughs, real zeros, null/long gaps and every threshold crossing while the
budget permits; excess evidence becomes explicit envelopes. No synthetic zero
or interpolated line is used for envelope uncertainty.

Detail summaries provide unit, UTC interval, local timezone, extrema values/times
and full statistical scope. Quality reports include BACKFILL and do not label
finite points good by default. Labels distinguish historical interval evidence
from current device state. Recorded analytics events have named keyboard controls
and continuation beyond seven loaded events. Their counts cover loaded evidence,
not an assertion that every threshold crossing or backend event was enumerated.

Inspect raw values loads one authorized 50-record export page on demand, with
UTC/local times, units, source and quality. Paired timestamp/source cursors keep
coincident source readings reachable. Previous/Next and retry issue reads only;
metric/range/actor changes discard old page state. The focusable table region
permits horizontal keyboard scrolling at narrow widths, and a page download
contains only the current response. Range and access limits remain server enforced.

Verification: frontend typecheck, full 56 files / 169 tests and production build
pass. Strict OpenSpec passes 37 items before archival; structure passes with
existing warnings. Sampling regressions cover several peaks/troughs, real zero, null/long gaps
and 999 crossings beyond the display budget. Loader tests cover latest failure,
disappeared latest temperature, device switching/abort and delayed parent callback
after interval change. Mapper tests prove full reducers are used; cursor/keyboard
tests exercise tied timestamps, raw page scope and event eight.

Telemetry Maven reactor passes 22 tests; follow-up TimescaleDB/MVC integration
adds coincident-source cadence and stable last-value regression. The real
Timescale fixture covers 2400 samples across the accepted 30-day range, late
peaks/trough/zero, BACKFILL counts, an interior gap, empty metric intervals,
range/bucket caps, source ties and denied-device access. No migration was needed.
The full reactor emitted a Surefire JVM-exit advisory after successful
tests; exit code and reactor remained successful. This is not capacity evidence.

Read-only live demo verification exercised historical metadata/envelope and
raw values with source/quality after restarting local telemetry-service. Synthetic
actual-component fixtures exercised 30000 points over 7 and 30 days while latest
failed, full summary/units/UTC/local times, keyboard Analyze/raw Next/event eight
and mobile detail. Native datetime input/change events apply exact seconds;
browser keyboard analysis confirmed a ten-second interval without widening. The dialog body has no horizontal overflow at 390/768 widths;
1440 also fits. Only the values table scrolls horizontally, with keyboard access.
Raw export limits and retention were preserved. AGENTS.md/project skills remain applicable.

## Accessibility and route delivery (implemented, accepted with verification exception)

Device and ML sections use shared tablist/tab/tabpanel relationships, selected
state and roving focus. Left/Right/Home/End move focus; Enter/Space activate the
section deliberately. ML workspace keeps `/settings/ml` and the existing tab
keys; Prediction policy labels the settings section. A mobile selector near the
heading exposes all eight sections. Navigation groups retain existing role
visibility and route URLs; USER dashboard access is not introduced here.

Agenda exposes separate entity-named Open, Finish and Cancel buttons; Enter on
Cancel does not open its operation. Fields associate hints and errors separately
and expose required/invalid state. Resource clocks stay outside live alerts;
inventory retries and ML lifecycle controls identify their records. Alert inbox
uses Acknowledge and entity-named incident actions. Neutral unavailable colors,
inline required markers and canonical theme variables replace touched ambiguous
styles. Inventory table rows become compact labelled entries at narrow widths,
preserving names, location, registration, telemetry age, risk and device links.

Authenticated shell and pages load through React.lazy. Loading and contained
error states offer explicit import retry and current-page reload without changing
the URL. The shell remains mounted across child navigation, retaining session and
draft providers. Failed imports never repeat API mutations.

Verification on 2026-10-08: frontend typecheck, 58 files / 173 tests, production
build; subsequent focused route/ML/keyboard regressions pass, including shell
continuity. OpenSpec strict 36 items and structure checks pass (existing warnings).
Read-only synthetic populated inventory and ML components fit requested viewport
widths 360/390/768/1024/1280/1440; native accessibility trees expose row names,
tab relationships and the selector. Browser arrows retain selected tab until
Enter. Doubled-font stress fits these widths with a 24px heading becoming 48px;
this is not a verified native browser zoom or screen-reader speech test. Those
two native checks were not executed because the connected in-app browser does
not expose them. On 2026-10-09 the user instructed closure and archive with
this limitation; native zoom/speech are excepted from this change's acceptance,
not recorded as passed. Product accessibility requirements remain in effect.

Deferred native verification procedure:

1. Record the browser/version, screen reader/version, operating system and
   test date. Use a test account with long device names and populated ML sections.
2. At viewport widths 360/390/768/1024/1280/1440, set the browser's own zoom
   control to 200%. Check inventory names, freshness, unavailable risk, required
   labels and primary actions. Check all eight ML sections remain discoverable;
   wide tables may scroll locally, but the document must not overflow horizontally.
   Record whether the browser uses page zoom or text-only zoom; CSS enlargement
   and viewport emulation alone do not satisfy this check. Restore zoom afterwards.
3. With NVDA or Narrator running, navigate device and ML tabs with Tab,
   Left/Right/Home/End and Enter/Space. Verify the selected state is spoken,
   arrows move focus without loading another section, activation selects it,
   and Tab reaches the associated panel.
4. Verify a success status and a significant error are spoken without moving
   focus; linked field hints/errors and entity-named row actions are understandable.
   In Agenda, activate Cancel with Enter and verify Open does not also run.
5. Record observed speech, each width's result and any failed scenario when
   these deferred checks are performed. Tasks 1.9/2.2 were closed by the explicit
   acceptance exception above. An accessibility-tree dump alone is not speech evidence.

Build main JS changed from 791.29 kB (219.38 gzip) to 204.21 kB (64.07 gzip).
Device details is 122.19 kB, ML workspace 151.50 kB, authenticated shell 8.75 kB;
shared dependencies also load on demand. CSS is 103.69 kB (18.45 gzip).
Login initial API requests remain zero in both before/after actual-App fixtures.
This demonstrates delivery separation and size, not measured latency improvement.

## Personal dashboard editor (2026-10-09)

USER navigation has one Dashboards entry with available dashboards as child
links and an authorized personal default on /dashboard. Metadata listing supports favorites, archive, search and sort;
blank/copy creates an independent private author draft. The editor configures
registered typed widgets through Data, Calculation/time, Presentation and
internal Drill-down fields. Units come from actual registered telemetry metrics.
Unsupported types remain opaque and cannot be configured as working KPIs.

View contains no edit controls. Edit uses twelve/six/one columns, collision
packing, manifest minimum sizes, sections, twenty Undo/Redo steps and pointer or
keyboard move/resize controls. Mobile order is independent. Changes autosave
at two seconds; failed saves retain only safe actor/account/dashboard/base-scoped
metadata recovery. Navigation offers Keep/Discard. Publication requires an
explicit reviewed summary and creates immutable history. A 409 compares the
latest server draft with local fields before load, independent copy or reviewed
reapplication. Archive clears favorites/default; restore retains identity.

Runtime device/location/time changes require Apply and remain in the safe URL
or explicitly saved personal defaults. They do not publish dashboard defaults.
Viewer polling reads intersecting panels only, pauses when hidden and backs off
on failures. Off/30 seconds/1 minute/5 minutes and manual refresh are available.
Snapshot/source/measurement clocks and stale failures remain visible. Kiosk is
an authenticated view with editing, publication and preference mutations absent.

Verification: 62 frontend files/183 tests passed, then eight focused dashboard
tests passed after adding the twenty-step history assertion. Typecheck/build and
29 dashboard-service Maven tests passed. The USER workflow fixture covers three
registered devices, create/real-query-shaped preview/publish/default/new mount,
archive fallback and absence of ADMIN navigation. Real local browser integration
used an existing ADMIN owner (private rights have no ADMIN bypass), three actual
demo device bindings and a fleet widget; fixed latest values retained their old
measurement timestamps. Two real tabs produced 409 and reviewed reapplication.
Keyboard reorder retained focus; a single-pointer menu needs no dragging.
Authored 200% text fitted native viewport widths 360/390/768/1024/1280/1440
without document overflow. This is not native browser zoom or a touchscreen
hardware test. Editor commit measured 11.50ms from definition receipt in the
local development browser, excluding definition/evidence API latency; this is
not a production latency or capacity claim. Generated logs live in ignored
artifacts/dashboard-editor-*; recovery never stores query values or credentials.
Additional final verification: focused dashboard/query/app tests 27 passed;
viewer keeps previously read offscreen panels in the same context to prevent
layout-driven request loops, and a regression test verifies context isolation.
Actual browser archive/restore preserved ID, final archive cleared the default,
and authenticated kiosk hides global navigation and the assistant. A stale
lifecycle ETag returns 409; reload/reconfirm is available without auto-retry.
Final full regression after kiosk/visibility fixes: 62 files, 185 tests passed
(2026-10-09). Focused typecheck/build passed; archive/default fallback also
verified against real local services after the request-loop correction.

## Business task template acceptance (2026-10-09)

Versioned task catalog and binding wizard are implemented for Shift, My devices,
Refrigeration and Technician. Optional café/store/laundry/property/workshop/IT/
service/home guidance does not change authorization. The Energy card explains
its unavailable aggregate capability. Templates contain symbolic slots; current
canonical bindings are resolved before private draft creation. Missing metrics
have explicit remove/keep-unbound choices. Registered units remain separate;
DEMO equipment and panel titles are labeled. Imported foreign UUIDs are not
accepted as ready bindings; current resource authorization and rebinding apply.
Supported v1 snapshots are independent, with source-version context notes.
Unsupported future versions are disabled with a blank/supported-version fallback.

Focused tests cover missing temperature with measured power retained, absent
energy capability, foreign/removed device rebinding, independent version copies,
canonical units and exact retry snapshot/key. Backend verifies active ownership
on catalog reads, rolling six-hour windows, unbound no_data without evidence
fanout and high/unknown risk presentation with null unknown scores. Browser
checks use existing local demo records and private owner permissions.

Usability observations here are agent-performed manager/owner/technician task
walkthroughs with elapsed timestamps, not a human participant study. The targets
(creation<=180s/configuration<=90s) describe these exercised local workflows;
no claim about real pilot participants, comprehension rates, production latency
or capacity follows. Unknown/no_data/stale/demo/scope are inspected separately
and never interpreted as zero/healthy, role upgrades or live fresh measurements.

Measured role walkthroughs (agent, local demo data):

| Task persona | Creation to editor | Configuration action | Observation |
| --- | ---: | ---: | --- |
| Manager / Refrigeration | 61.401s | 0.594s | Recorded temperature and W in separate panels, old timestamps visible, explicit publish |
| Owner / My devices | 88.689s | 0.335s | Three named devices; retry after local service restart preserved reviewed snapshot; no_data series not replaced by zero |
| Technician | 8.322s | 4.767s | Recorded risk filter, no assignment API controls, next-seven-day maintenance, explicit context note |

Creation clocks include intervening inspection/tool time; configuration clocks
cover the exercised local change/save action, not human reading or learning.
Each result is below180s/90s in this limited agent walkthrough. There were no
agent reinterpretations of unknown as healthy or of zero as missing. Human
misinterpretation rate is unknown. An additional Shift browser snapshot verified
an exact six-hour power interval (18:21:29Z to00:21:29Z) and no_data for old
out-of-window demo measurements. All four created test dashboards were published
as independent v1 and archived through the normal reversible lifecycle; no
personal default was assigned. Gateway/identity/dashboard Maven suite passed;
final dashboard suite33 passed. Full frontend64 files/189 tests, final focused
10 files/31 tests, typecheck/build, strict OpenSpec35 and structure passed.
Ignored evidence: artifacts/dashboard-templates-*. AGENTS/skills remain valid.

### Working screen interaction

The private dashboard editor now places the active screen canvas below compact
save/undo/publish/layout controls. Dashboard metadata/runtime and secondary
arrangement controls use disclosures. Add screen offers Blank, Monitoring,
Analytics and Incidents starts with distinct panels. Measurement panels start
explicitly UNBOUND until equipment and a registered metric are selected.
A keyboard-accessible native selector switches named working screens. Screen names
describe work rather than permission roles. Screen settings rename/reorder;
removing a screen explicitly transfers its widgets to the first remaining screen.
Widget transfer selects the destination and focuses the widget. Undo restores
the entire definition including screen ownership and layouts.

Add uses the header palette and explicit grid drops; relative placement of an
existing panel is available in panel settings → Layout.
Side directions explain minimum-width restrictions; mobile uses above/below.
Selected positions are pinned before downward collision resolution; unaffected
screens, sections and breakpoint layouts retain their positions. Normal empty
legacy layouts are accepted and new placements persist after editing.

Recorded KPI values emphasize numbers/units, including actual zero. History
uses observed envelope buckets with no invented lines across gaps. Incident,
risk and maintenance rows use recorded owner fields. Technical evidence is in
closed details; no_data/unknown/denied retain their existing distinct meanings.
Review publication checks all screens, including inactive denied configuration,
before showing explicit confirmation. This flow adds no team sharing or role
assignment.

Working-screen verification (2026-10-09, local Compose): an isolated USER account
with three explicitly DEMO devices and72 synthetic stored W samples created
Monitoring/Analytics/Incidents through the real UI (4/2/3 widgets). Fixed bindings
showed111W on Monitoring and a24-sample mean133.5W plus the observed history on
Analytics. Incidents displayed three UNKNOWN records with unavailable score/time;
empty incident/maintenance facts stayed no_data. All three screens were checked,
published as immutable v1 and reopened; Analytics selection survived reload and
authenticated kiosk. No personal default or team permission was assigned.

Pointer/keyboard checks exercised above/below/left/right, pinned collision
displacement, undo restoration, tab ArrowRight/manual Enter activation, widget
transfer with destination focus, and separate mobile order with unchanged desktop
positions. Editor and viewer checks covered360/390/768/1024/1280/1440 widths with
no document horizontal overflow. Rapid switching no longer reports transport
cancellation as connectivity failure; screen snapshots are isolated. An actual
409 during dev reload preserved local screen/layout changes; reviewed Load latest
restored the server draft while the published v1 stayed unchanged.

Backend dashboard suite35 passed, including real PostgreSQL screen lifecycle.
Full frontend68 files/200 tests passed; final focused dashboard22 tests and the
two workflow tests passed after the focus/link adjustment. Typecheck/build,
strict OpenSpec36 and structure passed. The first unlimited frontend run hit two
five-second timeouts during simultaneous service builds; both passed separately,
then the full suite passed with maxWorkers2. Evidence is ignored under
artifacts/dashboard-screens-*. This is an agent workflow check, not a human
usability study, native zoom/speech verification, production or capacity claim.
MinIO's unavailable default image was not changed; the exercised services ran
with existing infrastructure and --no-deps, without MinIO/ML verification.
AGENTS/skills still describe the repository workflow correctly.
## Dashboard card movement

The private editor keeps the dashboard title, save state, primary actions and
working-screen selection in the header. The full dashboard list is accessible
from settings; dashboard child links are in the left navigation. Destination
explanations and responsive settings are in the right drawer.
A dashboard contains named
working screens; cards contain the numbers, charts, tables and notes on them.
Saving a draft preserves its placement. Publishing updates the viewing version;
selecting it as the personal start page remains a separate explicit action.

Edit shows a 12/6/1-column placement grid. Each card has a visible title/drag
header and SVG Edit/Add controls. Drag the header to free grid space;
resize with the right edge, bottom edge or corner. The snapped layout commits
once on release, preserving manifest minimum sizes and collision prevention.
Escape or release outside the canvas cancels movement. Chart interaction and
touch scrolling remain available outside the drag header. Keyboard movement and
size presets remain in panel settings → Layout, with disabled boundary commands.
Undo/Redo and two-second autosave use the existing draft history. Settings have
Data, Calculation, Appearance and Navigation sections in the right drawer.

Movement/resizing changes only the selected screen and responsive layout;
other screens, breakpoints and the immutable published revision are preserved.
Viewers and kiosks keep a static grid without edit titles or resize handles.
The editor uses react-grid-layout and lucide-react; existing telemetry chart
rendering and backend-qualified evidence calculations remain unchanged.

Chart descriptions remain accessible, and gaps retain their original rendering.
Text in a user-created context-note panel is intentional content and is retained.

View information controls appear on hover or keyboard focus; touch exposes them
directly. Edit headers and primary controls are always visible. Working screens remain independent and only the active screen is read.
Final minimal presentation supersedes the earlier 177/232px layout measurements:
the desktop editor canvas begins at approximately 80px with collapsed navigation.

### Grid editor verification — 2026-10-09

Integration tests exercise the installed grid with mouse down/move/up events
for movement and corner resizing, cancellation and read-only/locked states.
Browser inspection confirms grid guides, eight visible corner handles, settings
sections and no page overflow in the populated demo. Browser control is DOM-only,
so a physical mouse drag in the live browser is not independently verified.

### Visual widget palette — 2026-10-09

Add widget opens a nonmodal right palette alongside the active grid. Search
filters visual cards for charts, current values, statistics, incidents, energy
and other server-manifest types. Drag a supported card onto the grid to preview
its size and snapped position; dropping inserts the panel and opens its right
configuration drawer. Add buttons provide the keyboard/mobile equivalent and
use a free position. The header is the single palette entry; there are no
repeated tile Add buttons. Unsupported types show their dependency and cannot
be dragged or added. The 24-widget limit covers all screens.

Unconfigured measurement panels start unbound until equipment and a metric are
selected. Visual palette previews contain no fabricated measurement values.
Drop changes only the active screen/section and responsive layout; collision
positions, Undo and draft autosave are preserved. Closing the configuration
leaves an explicitly unconfigured card; Undo can remove the insertion.

The installed grid external-drop pipeline is covered by integration tests,
including rejected payloads, placeholder mapping and other layout preservation.
Live browser native drag was available in this verification: dragging Current
value from the palette into free grid space opened its configuration. The test
insertions were undone without resetting the user's prior layouts/screens.

### Panel actions in settings — 2026-10-09

The card ellipsis and its floating form were removed. Edit opens the right
settings drawer; Layout contains size presets, keyboard movement, relative
placement, screen/section selection, duplicate and delete. Layout actions update
the draft immediately with existing undo/autosave and locks; data configuration
still uses Apply changes. Removing a panel closes its drawer. Unsupported catalog
types retain layout/management access without enabling data configuration.

### Panel information and navigation polish — 2026-10-09

Add widget in the header is the only palette entry; the repeated tile plus was
removed. Panel information uses label/value rows, full-width nested groups,
wrapped identifiers, readable UTC timestamps and expandable record lists showing
20 entries at a time with an explicit Show more button. Opening information still
uses the current authorized snapshot without another evidence request.

Desktop navigation has one workspace-header toggle with distinct PanelLeft SVG
states. Sidebar links use consistent SVG icons. On mobile the header opens a
native left navigation drawer; X, Escape or backdrop dismiss it and restore focus.
Navigation closes after route selection. There is no duplicate sidebar hamburger
or separate Menu/Close menu control. Kiosk remains without navigation controls.

### Drag restoration and configurable grid — 2026-10-10

During drag/resize, collision previews recompute from the gesture's original
layout. Neighbours return when the active panel no longer overlaps them; the
cursor path does not accumulate displacement. Returning to the starting position
creates no layout edit. Final overlap retains deterministic collision prevention.
Escape and outside release still cancel; completed gestures remain one Undo step.

Grid in the header opens the right drawer. Choose Computer/Tablet/Phone and enter
Columns (1–48), Row height (16–160px) and Spacing (0–32px), then Apply grid size.
Settings save for the current screen/profile and survive reload/publication/copy.
Existing defaults remain 12/6/1, 48px and 12px. Other screens/profiles are preserved.
Panel settings → Layout also has exact Width (columns) and Height (rows) fields.
Show grid guides is an editor-only toggle. Compact this layout explicitly removes
empty rows in the current profile and can be undone.

Panel minima are one column and one row, using edge/corner resize or exact
Width/Height fields in Layout settings. Separate insertion defaults preserve
normal 6x5 charts and 3x3 summary sizes. View uses the same fixed grid row height
as Edit; excess content scrolls locally. Dashboard plots measure their available
SVG area and recompute geometry without stretching labels or letterboxing tall
cards. Other telemetry pages retain default sizing. Very short cards reveal the
edit toolbar on hover or keyboard focus to leave space for their content.

### Unified dashboard navigation — 2026-10-10
The sidebar exposes one Dashboards entry for users and administrators. It opens
the /dashboards list and is active across catalog, viewing and editing routes.
All dashboards in viewer Manage settings or editor Dashboard settings opens the existing
catalog (/dashboards), also opened by selecting the Dashboards parent entry.
Copying and preferences reside in the concrete dashboard settings. The
separate My dashboards sidebar and workspace-header entries are removed. Screens
remain the existing selector within each dashboard. Stored routes and data are
unchanged.

The dashboard catalog is a compact list. New dashboard opens a creation drawer
with Blank/Templates choices. Copy, favorites and personal default are commands
in concrete dashboard settings. Viewer Dashboard settings also contains runtime
filters, period, refresh interval, manual refresh and kiosk entry; no runtime
control bar appears over the canvas. The refresh timer belongs to the viewer,
so closing the settings drawer does not stop automatic refresh. Existing auth,
visibility pause and exponential backoff behavior are preserved.

Available active dashboards appear as child links under Dashboards in the left
navigation. Published dashboards open View; unpublished dashboards open Edit.
The current child is highlighted on both routes. Names are loaded through the
existing actor-scoped paginated metadata endpoint; archived items remain in the
catalog archive filter. Sidebar hide/restore and mobile drawer behavior persist.
Viewer settings have Data and time, Refresh and Manage sections with vertical
fields and distinct management groups.

### Dashboard management from navigation (2026-10-10)
The Dashboards parent link opens /dashboards directly; child links open concrete
dashboards. The sidebar contains navigation links only. Creation and group
visibility controls are in the catalog.
There is no search field in the sidebar; search and sorting remain in the catalog.
Each catalog row has an Actions button that opens management for that
concrete dashboard: default, favorites, independent copy, edit, menu visibility
and permission-checked archive/restore. Archive requires confirmation and the
fresh draft etag; hiding changes only personal navigation. Hidden and Archived
filters allow recovery. Expanded state and hidden ids persist locally per user
in this browser, with an in-memory fallback if storage is unavailable. Published
items open View and unpublished active items open Edit. Legacy catalog links and
viewer runtime settings remain supported. Backend contracts are unchanged.
