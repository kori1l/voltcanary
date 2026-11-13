---
name: voltcanary-openspec
description: VoltCanary OpenSpec workflow for non-trivial product, architecture, dependency, API, data, security, deployment, cross-service, ML, analytics, alerting, recommendation, and frontend workflow changes. Use when Codex needs to select, apply, validate, update tasks for, or archive an OpenSpec change.
---

# VoltCanary OpenSpec

## Decision

Use OpenSpec before implementation when the work affects product behavior,
service APIs, DTOs, event schemas, database shape, retention, security,
deployment, cross-service flows, ML semantics, analytics, alerts,
recommendations, or frontend workflows backed by backend behavior.

Bypass OpenSpec only for small local fixes that do not change those surfaces.

## Workflow

1. Run `npm run openspec:list` unless the user already named a change.
2. If the user named a change, run `npm run openspec:apply -- <change-id>`
   before editing implementation files.
3. If no change was named and the user asks for the next change, select from
   the current `openspec:list` using the applicable sequence below. Honor an
   explicit AI, security, UI/UX, dashboard, notification or ML scope; check prerequisites
   before applying. Ask for change selection only when no applicable sequence
   or unambiguous active target resolves the request. Never select archived
   changes or infer completion merely from absence in the active list.
4. Read the applied change's `proposal.md`, `design.md`, `tasks.md`, and changed
   specs under `openspec/changes/<change-id>/specs/`.
5. Follow the task checkbox format printed by `openspec:apply`. Check a box only
   after implementation and verification for that task are complete.
6. Run `npm run openspec:validate` before broad changes when feasible and
   always before the final response for OpenSpec-governed work.
7. Use `$voltcanary-docs-guidelines` when the change affects docs, contracts,
   architecture notes, operating guides, or agent workflow.
8. Before finalizing, check whether `AGENTS.md` or project skills need updates
   because the change altered repository structure, agent workflow, validation
   commands, or architecture rules. Update them only when they would otherwise
   become stale.
9. Archive with `npm run openspec -- archive <change-id>` only after every
   task is complete and required validation passes.

## Notification Work Sequence

When the user asks for the next active notification work and no change is
explicitly named, prefer this order while these changes are active:

1. `modularize-alert-notification-boundary`
2. `add-email-telegram-notification-adapters`

Implement the boundary first, or apply both changes in the same implementation
pass only if the adapter work still goes through the new port, snapshot request,
provider registry, and focused frontend notification settings modules. Do not
add provider-specific delivery branches directly to alert lifecycle code or large
route pages.

## Explicit Spring AI Migration

The user-authorized `rebuild-assistant-with-spring-ai` change owns Spring AI 2 and
the prerequisite Spring Boot 4 upgrade. During this explicit migration it takes
precedence over the A5/A6 sequence below. Reuse existing domain contracts and
preserve their unfinished quality gates; framework migration does not qualify
model quality or authorize paid evaluation.

## AI Assistant Orchestration Sequence

The user reprioritized delivery on 2026-11-11: **AI agent first, Java,
assess LangGraph4j**. Follow the authoritative
[AI-first priority](../../../openspec/changes/README.md#ai-first-execution-priority)
and `adopt-langgraph4j-agent-orchestration` before broad configurable G0/G4 work
or the former unconditional A5 -> A6 selection. Core1.9.9 is now integrated for
new turns/context/confirmation with atomic schema2 plan checkpoints. Use its
actual unchecked tasks and evidence for continuation; do not repeat completed
compatibility work or claim specialist/in-place migration/model qualification.
Reuse Spring AI, one ledger and existing domain owners. Existing
A3/A5/V/R/G7 criteria remain open and integrate through that one library runtime.
Only exact missing owner prerequisites may interrupt this agent-first sequence.
The older paragraphs below describe platform progress/internal dependencies;
they do not override the new priority. Planning does not authorize live calls.

For configurable tables/relations, map features, temporal rules and their AI
authoring, use the
[configurable operations program](../../../openspec/changes/README.md#configurable-operations-program).
It supersedes specialized transit T1–T7; HSL is a package/example, not a core
model. The user explicitly resumed configurable implementation on2026-11-03.
G0 scalar task1.3 and bundle storage infrastructure are verified. Continue G0
task1.4 real owner adapters/validation and bundle integration, then1.5 UI/AI use.
Use prerequisite G1/M1 owner slices where configurable metadata is still absent,
as the index directs; do not count test attestations as real owner integration.
Document-bound static RULE source/native/grant/policy composition now exists.
It returns STATIC_TYPED_IR only, requires every exposed schema field in
the grant, and does not qualify runtime/cost readiness or public bundle admission.
Native schema/review/provision/describe, reviewed single-row CAS/history capture
and bounded LIVE queries now exist in device-service. G6 provides the first native
/data table/row workbench and shared registry publication. Continue actual
owner/bundle wiring. G7 has default-off native table authoring through the existing
review/model pipeline, shared preview and exact receipt/schema readback; broader
AI/M1 and live model qualification remain open. Source binding schema journals and signed metadata
exist, but their legacy profile cannot qualify capture/temporal readiness. RULE
bundles still need actual capture readiness plus grant/evidence-policy owners.
Alert-service RULE identity reservations are the first grant/review prerequisite;
reserved IDs alone grant no authority. Reviewed source/native-field grants and local
epoch revocation and signed compiler grant adapter are implemented. Reviewed
private-reference evidence-policy metadata and its signed grant-bound adapter
are implemented too; actual evidence storage/retention, atomic alert/effect
admission remain separate gates. Actual normalized input generation/cutover and
the signed source capture readiness adapter are now implemented (G3/4.4–4.5,
G0/2.7), with legacy facts unchanged and all upstream/negative/runtime flags
conservative. Continue qualified operator/readiness/cost checks and actual owner
composition before qualifying the public bundle path or marking G0 complete.
G4/5.1 now provides pure bounded FIXED/ROLLING/calendar window assignment with
timezone replay pins; actual aggregates, coverage/finalization and durable runtime
remain prerequisites. Do not advertise the assignment kernel as an executable
window_aggregate operator or evaluator readiness.
G4/5.2 adds pure interval union/exclusion for supplied typed batches. It preserves
unknown selected intervals/breaks and exact clipping, but supplies no complete
evidence, lower-bound emission permit, durable correction or effect admission.
G4/1.2 shares the completed G0 scalar implementation; do not reimplement those
kernels as another temporal interpreter. Continue unmet owner/runtime prerequisites.
G4/5.3 supplies pure COUNT/SUM/MIN/MAX/AVG over bounded typed batches with pinned
decimal policy and exact window membership. Stateful grouping, completeness,
finalization, durable corrections and effect admission remain pending; do not
advertise a pure reduction plan as an executable RULE runtime.
G4/5.4 adds pure root timing/skew/frontier/idle arithmetic. Qualified owner
progress, durable monotone watermarks, input-generation/state and actual
coverage/finalization remain pending. Idle expiration supplies no exit/absence
proof and does not close G4/3.1.
G4/5.5 supplies isolated keyed state/inbox storage with immutable pins and database
lease/CAS fences. Continue5.6 atomic timers/derived/rekey/outbox and actual owner/
generation/coverage/evaluator integration; the1480-test backend checkpoint and26
subsequent focused timeout cases do not qualify an activated durable RULE runtime.
G4/5.7 adds atomic timer schedule/discovery/consumption infrastructure, with44
H2/PostgreSQL cases and521 analytics-reactor tests. Continue the unmet5.6 outbox/
derived/rekey and owner/generation/coverage/evaluator work; timer intents and idle
expiration cannot prove observed exit, completeness or finalization.
G4/5.8 adds atomic output/ACK metadata (70 H2/PostgreSQL cases,547 analytics
reactor tests), with no sender or qualified effect authority. Continue actual
owner/grant/generation/coverage and evaluator integration; do not close5.6/1.4/4.3
from private infrastructure tests or activate RULEs from stored output receipts.
G4/5.9 adds atomic local REKEY consumption and its verified source ACK into a
supplied target state/timer/output transition. Preserve ordered dual-key leases,
source-qualified receipt namespaces and exact pin/replay fences. The102 focused
H2/PostgreSQL cases and593-test analytics reactor do not qualify an autonomous
evaluator/worker, actual owner/grant/generation/coverage admission or alert effects.
These increments do not complete G1/M1;
SNAPSHOT/import/migration/sharing and full acceptance remain open.
Do not skip unmet prerequisites or restart the retired transit queue. The index execution-status section
is authoritative; earlier stop notes in design history do not revoke this resume.
Follow G0/G1/GX/G2–G8 owner dependencies and M0–M4 vertical milestones,
integrating UI/AI/acceptance in each slice. VCL types/methods, dense native storage,
customer DB adapters and strict imports are normative; do not revive tentative
EAV/optional-interpreter or transit-only plans. Reuse existing resources,
assistant/ledger/V/R and unfinished A5/A6 gates. For requirement clarification, nested specialists and
RAG use [the collaboration contract](../../../docs/target/ai-assistant-collaboration-and-retrieval.md):
one durable runtime/ledger, typed slots, bounded scheduled consultations, exact
owner metrics and optional ACL-aware knowledge index. No recursive ChatClient
callbacks or unreserved embedding/reranker I/O during prompt serialization.
`npm run vcl:check` and `npm run ai-contracts:check` check documentation, not
runtime/model qualification. Spring framework bindings and S0-S6/SPR01-SPR15
are owned by the [implementation contract](../../../docs/target/ai-assistant-spring-implementation.md)
and proposed ADR-014, through the same A5/A6/G7/G8 changes; do not reopen the
completed Spring AI migration or infer live/implementation authorization. Do not route unrelated AI work
into this program or infer paid/live authorization from planning.

For the current assistant architecture scope (model-selected intents, dependent
plans, server-owned skills, progressive context, economical models/provider
portability and shared budgets), use the
[orchestration program](../../../openspec/changes/README.md#ai-assistant-orchestration-program).
It supersedes the C3 -> stages5/6 implementation order while preserving their
unfinished acceptance requirements, existing history/trace/execution baselines
and failed-quality/default-off release gates. Continue an eligible in-progress
new-program member, otherwise select its earliest unfinished eligible change.
For the 2026-10-28 amendment, remaining program stages are A5 then A6: improve
core skills/context/output/workflows and record first-pass results before adding
V/R resilience at the end of A5. Diagnostic profiles disable automatic correction
and model judges, retain schema/access/confirmation guards and count necessary
planner/step/context calls. A6 reports first-pass, recovered and final success
separately. Follow the index's scoped prompt; do not infer live authorization.
Do not require old C3 phrase-matching acceptance before starting new contracts,
or require full verifier/recovery archival before their integration and shared
qualification; the index owns these noncyclic gates. Planning-only requests
authorize contracts/proposals/guidance, not runtime implementation or paid trials.
Configurable implementation is resumed as stated above; prior live/paid model
campaigns remain stopped until explicitly authorized. Do not infer provider-setting
or notification-sending authorization from the implementation request.

## AI Dashboard Reliability Sequence

For AI dashboard conversation reliability (names/pending state, capability
resolution, semantic edits, outcome verification, recovery and dialogue evals),
use the [reliability program](../../../openspec/changes/README.md#ai-dashboard-reliability-program).
For the redesigned assistant, follow the orchestration section above first.
The old index preserves the six-stage requirement ownership; each proposal owns its amended hard
prerequisites. For an unqualified "continue" or "next change" in this conversation
scope, continue an eligible in-progress member of that program, otherwise select
its earliest unfinished eligible stage. Do not use list recency or an older
preview change's checked-task count to override that order.

The first stage is `establish-ai-dashboard-conversation-evals`: establish the
real-dialogue baseline before changing runtime behavior. Baseline completion may
record known failures; it is not final model-quality acceptance. Later default
enable remains gated by the outcome-verification and recovery release checks.

Unfinished preview tasks11/12 are delegated acceptance traceability, not a second
implementation queue or a prerequisite to finish/archive before this program.
Keep their unchecked status until successor evidence satisfies them. Existing
completed preview/catalogue/history work is reused. Explicit user targets retain
priority subject to dependencies; unrelated UI, deletion, device, security and ML
requests do not automatically enter the reliability program. Planning/order-only
requests authorize updating guidance, not implementing the queued changes.

## AI Creation And Identity Hardening Sequence

For the redesigned assistant's intents/skills/plan/provider scope, the orchestration
section above takes precedence; this section preserves historical creation and
identity baseline ownership and applies to explicit unrelated security work.

For requests to implement, continue or select the next AI device/dashboard
creation change, use the [AI creation program](../../../openspec/changes/README.md#ai-creation-program).
That index owns the ordered queue and dependency explanations; each proposal
owns its hard prerequisites. Do not route AI-created dashboards through the
historical UI/dashboard queue below.

Use the current active list, continue an eligible in-progress program member,
then choose the earliest unfinished eligible member in the program's order.
For a narrower request, consider its target and transitive hard prerequisites:
identity hardening precedes release of the new workflows, isolated execution
precedes both creation features, and device creation is required for dashboards
only when the requested workflow includes the newly-created-device handoff.
For a security-only request, select identity hardening without adding AI feature
implementation. An unqualified continuation uses this program when the active
conversation is about it; do not make it the global priority for unrelated work.

Use the same completion/evidence gates as the main workflow: absence from the
active list does not prove completion, proposal artifacts are not progress,
and completed active work is finalized before moving on. Preserve authenticated
autonomous testing: migrate header-only fixtures/scripts in the security change
instead of retaining a runtime identity bypass. Planning or order-review requests
authorize updating proposals/guidance only, not implementing the queued changes.

## UI/UX And Dashboard Work Sequence

For requests to implement, continue or select the next ordinary UI/UX/dashboard change,
read [the execution sequence](references/ui-dashboard-sequence.md). It owns
the historical UI queue and current scope filters/selection rules. AI dashboard
conversation reliability follows the dedicated section above instead.
Use the current active list and each proposal's dependencies; the reference
does not authorize implementing changes when the user only asks to plan or
document their order.

The [program index](../../../openspec/changes/README.md) owns requirement
coverage and dependency explanations. Keep its summary aligned when changing
the execution policy; do not copy the full queue into AGENTS.md or other
surface skills. Explicitly named changes and user-specified priorities take
precedence over the default queue, while technical dependencies must still be
resolved.

## Context

Treat `openspec/project.md` as the project-level policy. Use `docs/api/*.md`,
`docs/events/*.md`, `docs/architecture/*.md`, `docs/adr/*.md`, and
`docs/flows/*.md` as source context that must stay aligned with implemented
OpenSpec work.

Ignore archived change directories unless the user explicitly asks to inspect
or restore archived context.
