# Dashboard conversation evaluation tooling

This is a local evaluation harness, not a production planner or a new API route.
The `offline-reliability-v1` driver renews near-expiry tokens before bounded AI
submissions, initializes a foreign actor's account before scope checks, and uses
separate conversations with an explicit immutable parent for independent branches.
Recovery reads `acceptedReview` as a read-only preview when the latest attempt
failed; it never promotes that failed attempt to READY. Corpus and grader remain
unchanged, but this driver change must be reported when comparing historical runs.
Deterministic trace/history fixtures are local assistant-v2 HTTP responders and
do not establish model quality. Real-model campaigns remain separately authorized.
See the authoritative [AI verification runbook](../../docs/current/flows/ai-creation-verification.md#multi-turn-conversation-evaluation).

| Module | Responsibility |
| --- | --- |
| api.py | Ordinary gateway auth/HTTP, safe failures, source/config fingerprints |
| account_lock.py | Prevent overlapping planning inventory and fixture execution/cleanup jobs on one account |
| fixtures.py | Fresh synthetic GPS/boolean/text observations and owned cleanup |
| grading.py | Independent requested-outcome, identity/binding/order checks |
| runner.py | Real multi-turn planning, recovery, branching and trial reporting |
| conversations.py | Durable conversation submission plus per-turn authenticated history/trace export, including transport failures |
| observer.py | Explicit opt-in disposable-provider call/usage observation and one labelled preparation fault |
| execution.py | Separate confirmed execution, exact draft comparison, renderer reference and cleanup |
| state_smoke.py | Revisioned state, exact request replay, controlled provider failure and owner recovery through ordinary APIs |
| capability_smoke.py | Shared metadata/view contract, actual source identity, boolean/text history, stale/false evidence and foreign access |
| source_choice_smoke.py | Actual model source question, explicit scoped answer, provider fault and continuation with the saved source |
| preview-reference.html | Temporary Vite-root harness for the actual shared preview, with1100/420 px containers; no auth token |
| test_evaluation.py | Offline tests that deliberately corrupt results and challenge graders |
| test_conversations.py | Durable replay correlation and diagnostic collection after transport failure |

Entrypoints are `../ai-dashboard-conversation-evaluation.ps1`, its Python equivalent
and `../ai-dashboard-conversation-execution.py`. Runtime UUIDs, reports and screenshots
belong under ignored artifacts/. The frozen corpus/schema live under ai-agent-service
test resources. Historical template scripts/fixtures remain separate and unchanged.

Stage4 follow-up entrypoints: `../ai-dashboard-history-evaluation.py` reuses the
frozen outcome predicates through complete conversations; `--cases` selects exact
case IDs, `--workers 1|2` bounds concurrency. `../ai-dashboard-stage-four-execution.py`
prepares a compound real-model review, confirms its exact immutable payload,
compares the created private draft and keeps only its named QA resources for
browser inspection. Run its `--cleanup-handoff <output>/summary.json` after
inspection. Both require an ignored `--account` metadata file and environment-only
`VOLT_EVAL_PASSWORD` (or transient access/refresh tokens). Do not save credentials
in metadata. Account leases prevent planning inventory checks overlapping execution.
No global model/provider setting or semantic rollout default is changed.

`../ai-created-device-continuation-smoke.py` verifies durable created-device
referents through the ordinary authenticated conversation and execution APIs.
It observes public HSL topic vehicle identities without storing readings, creates
two isolated named QA buses, checks reload, exact device/source/field bindings,
reviewed/created equality, explicit override and deleted-referent rejection.
It injects no telemetry. `--keep-for-browser` retains successful QA fixtures;
after inspection run the same entrypoint with `--cleanup-handoff <run>/summary.json`
and a fresh `--output` directory. Cleanup verifies names and waits for ordinary
device-deletion receipts. Failed trials retain their trace and raw FAIL result.

Each live turn writes `conversations/<conversationId>/<requestId>.json` with
redacted server history and safe trace; `diagnostics.json` indexes the evidence.
`summary.json` preserves failed predicates and a separate failure analysis.
An outcome mismatch may be interpretation, compilation or a predicate limitation;
inspect actual candidates and definitions before assigning a cause. Historical
scores remain unchanged when a follow-up audit identifies a predicate limitation.
Run offline checks as `python -m unittest scripts.ai_dashboard_eval.test_evaluation
scripts.ai_dashboard_eval.test_conversations`; this preserves the package identity
used by the existing mock patch targets.

`../ai-dashboard-task-state-smoke.py` is the separate state-contract job. It requires
an explicitly disposable OpenAI provider, restores temporary settings/URL changes,
and labels semantic quality separately from state-contract results. Its optional
transient auth follows the same environment-only contract as the conversation runner.

`../ai-dashboard-capability-smoke.py` runs the read/preparation contract without a
model. `../ai-dashboard-source-choice-smoke.py` exercises source selection with a
real model and an explicitly disposable provider. Both decommission synthetic
fixtures; the source-choice job also restores provider/model settings and checks
that dashboard inventory is unchanged. See the runbook for exact invocation.

A5 diagnostic entrypoint: `../ai-first-pass-checkpoint.py` freezes the original
families and two held-out paraphrases without format/domain correction or a
model judge. It inspects the authenticated runtime profile before each turn,
counts required planner/step/context calls through A2 trace purposes, preserves
failed/unreached steps and writes to a new artifacts directory. `call_accounting.py`
separates first-pass/recovered/final metrics; absence of complete call evidence
stays unknown. `--previous-campaign` links immutable reports and consumes the
same remaining aggregate limits, including elapsed wall time. Such a linked
source-version reevaluation is explicitly non-independent and never replaces
its prior failed score. This is an A5 development checkpoint, not A6 qualification.
