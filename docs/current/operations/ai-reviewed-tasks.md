# Reviewed assistant tasks

## Spring AI runtime

Backend services build on Spring Boot 4.0.9; the assistant uses Spring AI 2.0.2.
After rebuilding backend images, restart changed application services; no database
reset or provider-secret rotation is required. Keep existing provider base URLs:
OpenAI and compatible endpoints use Chat Completions with their configured base
prefix (the default OpenAI base ends in `/v1`), Anthropic uses `/v1/messages` and
Gemini uses GenerateContent. Custom Responses-only proxies must expose Chat
Completions before cutover. `AI_ASSISTANT_V2_ENABLED=false` disables new generation
with HTTP 503, while stored history/reviews and confirmed execution remain
available. It does not activate a rules fallback. See the
[Spring AI runtime](../architecture/spring-ai-assistant.md) for ownership and tests.

Run the backend test suite and local HTTP fixture tests before enabling a rebuilt
runtime. The evaluation observer and trace fixture accept Chat Completions.
Do not treat offline success as paid/live model-quality qualification.

## Inspecting an assistant turn

Open **Этапы запроса** below a submitted message to inspect observed context reads,
actual provider attempts, rejected candidate fields, compiler checks, saved plans
and execution results. Expand technical details or use **Экспорт диагностики**.
Trace reads/export require the same owner and actual account scope as the chat.
Loading history or a trace never asks the model to approximate a past result and
never repeats a domain command.

Provider/model-run SUCCESS means a provider response was processed; check
sanitization, compilation and review status separately. Execution success comes
from the existing domain receipt. `PROCESS_INTERRUPTED` means a required stage
has no durable outcome; `RECEIPT_RECOVERY` uses an existing scoped result without
executing again. `DETAIL_LOSS` reports optional diagnostic loss. A required start
or pending outcome that cannot be stored blocks a later side effect; do not
retry an uncertain mutation merely to repair its trace. Check the plan/receipt.

Trace payloads exclude credentials, headers, raw upstream bodies and private
reasoning before writes. Limits and conversation-aligned retention are defined in
the [API contract](../api/ai-agent-api.md#observable-conversation-traces).

Deterministic trace acceptance runs against a disposable local HTTP provider:

```powershell
$taskFixturePath = Join-Path (Get-Location) 'scripts/ai-trace-fixture-provider.py'
docker run -d --rm --name voltcanary-ai-trace-fixture --network voltcanary_default --mount "type=bind,source=$taskFixturePath,target=/fixture.py,readonly" python:3.12-slim python /fixture.py
powershell -ExecutionPolicy Bypass -File scripts/ai-assistant-trace-docker-smoke.ps1
docker stop voltcanary-ai-trace-fixture
```

This fixture has no published host port, logs no prompts/headers, creates no
devices and uses isolated accounts through ordinary authenticated provider APIs.
It proves missing-context and rejected-field capture and safe export; model
quality is evaluated separately with the real configured provider.

Use the existing authenticated model-provider settings and normal gateway JWT.
Gemini credentials are sent as `x-goog-api-key`, never as query parameters.
Do not place provider keys, source credentials or JWTs in task context, fixtures,
logs or evaluation reports. Provider configuration remains in its existing
verified-user namespace; task authorization separately resolves the real device
account and does not assume its ID equals the user ID.

Complete conversations now restore automatically from ai-agent-service, including
selection, submitted messages and outcome receipts. Unsent drafts are separate,
owner-scoped and redacted. Legacy **Восстановить последнюю задачу** recovers only a
labelled task, not a reconstructed transcript. **Check task status**
reads the authorized persisted result. Changing sessions invalidates an open
confirmation. A SUCCESS result opens the resource returned by the domain API.
For UNKNOWN, inspect status first. Only a supported receipt-backed creation
offers reconciliation; it uses the original reviewed payload/key and current JWT.
For an unsupported legacy operation, inspect the domain resource before preparing
another task. There is no automatic compensating deletion.

The light assistant is an application-level overlay, not a widget embedded in a
device page. Its window menu selects floating/sidebar-overlay/expanded and left/
center/right where applicable. Conversation and unsent text remain in memory when
minimized or navigating; presentation preferences alone are stored persistently.
Attach a device through **Добавить контекст** in a new conversation. No page context
is silently attached. During a dashboard review, use the same composer to refine
panel order, widths, heights or period. Creation confirms the displayed version.
The preview uses real authorized dashboard reads; unavailable data is not generated.

If the account is not set up, initialize it through the ordinary application
flow before preparing an assistant task. If permissions or selections change,
prepare and review again. Never edit task rows to reset a mutation to READY.
An expired task requires new preparation. The default retry window is 24 hours;
task retention is 30 days after expiry and device receipts are retained 31 days.
Configurable task expiry must be positive and no longer than 24 hours so the
execution retry window always fits inside owning receipt retention.

Backend verification uses `DurablePlanPostgresTest` and
`DevicePostgresIntegrationTest` with real PostgreSQL Testcontainers. The former
exercises independent stores, parallel claims, restart reads, expiry, interrupted
leases and scoped access. The latter exercises parallel keyed device POSTs,
payload conflicts, rollback, decommissioning and distinct accounts.

The frozen Russian device fixture is
`backend/services/ai-agent-service/src/test/resources/evaluation/device-intent-ru-v1.json`.
For live quality, run `scripts/ai-device-model-evaluation.ps1` with a dedicated
test account metadata file and its password supplied locally. Configure a small
model through ordinary provider APIs first. Reports go under ignored `artifacts/`;
they record fixture hash, versions, model, latency and actual outcomes. The
script prepares all 40 requests without executing any mutation and requires at
least 18/20 correct clear reviews and 20/20 unresolved cases with zero actions.
A missing/unavailable provider is a failed or blocked gate, never a PASS.

Dashboard verification uses the separate frozen `dashboard-intent-ru-v1.json`
fixture and `scripts/ai-dashboard-model-evaluation.ps1`, with the same 18/20 clear
and 20/20 unresolved thresholds. It prepares only; run execution acceptance after
the evaluation finishes so resource counters remain meaningful. Infrastructure
failures before model invocation are recorded separately and are not model scores.
`scripts/ai-dashboard-fixture.ps1` provisions a dedicated synthetic fridge/source
through ordinary APIs and ingests explicit zero power and temperature. Tokens are
transient; metadata and evidence go to ignored artifacts.

`scripts/ai-dashboard-execution-docker-smoke.ps1 -InjectResponseLoss` checks two
accounts, mixed/foreign bindings, cancelled/stale reviews, six concurrent confirms,
private draft replay and unchanged preferences. Its optional response-loss fixture
commits the reviewed definition with the actual stable domain key, then marks only
that verified fresh task/action UNKNOWN in local PostgreSQL and restarts AI-service.
It leaves payload/version/digest/owner unchanged and requires receipt recovery to
return the same draft. This is local fault injection, never a production repair.
Created dashboards are archived and the foreign fixture device decommissioned.
The widget checks preserve an accepted numeric zero in the latest asOf snapshot.
For no-data evidence, the script uses the ordinary draft editor API to set a
history panel to an empty fixed interval; a latest snapshot is not an interval series.
The shared synthetic source fixture is cleaned separately after browser checks.

AI dashboard preparation is read-only and uses current authorized template data.
Templates needing tariffs, counters, cycles or production periods and ambiguous
multi-source equipment require the ordinary editor. A draft is private and
unpublished; source connectivity, no-data, publication and personal defaults retain
their ordinary product semantics. Dashboard creation receipts last 24 hours;
confirmed UNKNOWN reconciliation reads them before attempting any new preparation.


## Conversation evaluation operations

The modern multi-turn harness is separate from historical template evaluation;
see the [runner and release protocol](../flows/ai-creation-verification.md#multi-turn-conversation-evaluation).
Use dedicated account/provider metadata, transient login/token environment variables
and pinned nonsecret model settings. Offline harness tests use standard-library
unittest. Live preparation runs leave dashboard counts/preferences unchanged and
clean only their EVAL-marked source/device fixtures. The separate explicit execution
job checks exact review/draft equality and receipt replay before owned cleanup.

Opt-in local provider observation temporarily reroutes a disposable OPENAI provider
and restores its base URL in finally. Do not use a shared/user provider. After a
forced interruption, restore the original URL through the ordinary authenticated
provider API and inspect only the exact interrupted run's fixtures for cleanup.
No JWT/provider/source key is persisted in corpus, reports or screenshots. Observed
call/usage counts are distinct from unknown values when observation is disabled.
A failed-quality baseline is useful evidence and must not be relabelled PASSED.


## Dashboard working-state operations

DashboardTaskConversation coordinates typed DashboardTaskState/Fact/turns
through the DashboardTasks outbound port and JdbcDashboardTasks adapter. The
existing PlanStore remains the immutable review and execution authority.
PlanClarifications/context_json are compatibility adapters, not the new owner
of accepted dashboard facts. CreationTaskPlanning checkpoints grounded name
patches before compilation and never presents a known-field question as new input.

Turn acceptance commits PENDING before remote work. Optimistic branch revision
and the unique actor/account/requestId constraint prevent silent overwrite and
duplicate planning. A15-minute lease bounds interrupted work; the minute scheduler
records INTERRUPTED after expiry, preserving names, pending changes and last review.
Task GET is read-only. It never expires a lease or starts a replacement provider
call. A fresh request can continue a recovered failed turn at its current revision.

V003 is additive. Rollback to the prior application leaves the new tables intact
and keeps legacy reviews/receipts executable under the existing rules. Do not
delete reviewed plans or reset RUNNING/SUCCESS/UNKNOWN to retry preparation.
Working branches share task expiry and at least30-day post-expiry retention;
branch retention cleanup cascades to its turn rows. Restored branches do not
extend the original expiry; legacy upgrades are bounded by parent expiry.

Use the existing ignored evaluation account and transient normal JWT/login.
The optional scripts/ai-dashboard-task-state-smoke.py explicitly requires a
disposable provider: it matches nonsecret model settings temporarily, observes
real OpenAI requests, injects a labelled connection failure into that test
provider only, and restores both URL/settings. It creates measured owned fixtures
and decommissions them; it never executes a dashboard. Its Docker PostgreSQL probe
reads only scoped model-sequence counts, not messages, credentials or raw results.
The default local AI database name is used by this probe. Keep reports under
artifacts/ and never use the observer/fault switch on a shared provider.

Long evaluation sessions use normal login and rotating `/auth/refresh` sessions.
The harness retains refresh tokens in memory only; transient access-token callers
may provide `VOLT_EVAL_REFRESH_TOKEN`. Foreign-identity checks use their own client
and optional `VOLT_EVAL_FOREIGN_REFRESH_TOKEN`, never the primary session's refresh.
No test helper may mint a substitute user token to extend the evaluation window.
The semantic protocol is enabled only for an isolated local run with
`AI_DASHBOARD_OPERATIONS_ENABLED=true`; retain the default false until the outcome
and final recovery gates pass. Never use a user provider with the fault observer.


Stage4 corrective verification uses synthetic VehicleFixture telemetry through
ordinary device/source and ingestion APIs. Night-time absence of real bus service
must not be counted as a compiler regression. Keep stale/no-observation cases
separate from fresh fixtures, and require readiness of identity/device/telemetry/
dashboard/AI services through the gateway before starting model jobs. A gateway
health response alone does not prove downstream readiness after recreation.
