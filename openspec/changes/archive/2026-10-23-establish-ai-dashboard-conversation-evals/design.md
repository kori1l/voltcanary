# Design: Conversation outcome evaluation

## Reuse points
- `scripts/ai-dashboard-model-evaluation.ps1`: preserve its template compatibility
  job; extract reusable login/API/report functions only where necessary.
- `scripts/ai-dashboard-fixture.ps1` and
  `scripts/ai-dashboard-execution-docker-smoke.ps1`: reuse ordinary setup, receipts
  and cleanup; do not create another privileged execution route.
- `backend/services/ai-agent-service/src/test/resources/evaluation/`: add
  `dashboard-conversation-ru-v1.json` and a schema documented alongside the runner.
- Existing `DashboardIntentEditsTest`, preparation tests, PostgreSQL task tests and
  `frontend/src/aiAgent/useAssistantConversation.test.tsx` remain boundary checks.

## Corpus contract
Start with at least24 independent scenario definitions, including eight critical
multi-turn sequences. Each case declares stable ID, fixture profile, explicit user
messages, expected outcomes per turn, permissible clarification, capability
requirements, mutation policy and cleanup. Freeze the user's original spelling in
core replays; include paraphrases in a held-out subset not used for prompt tuning.
Runtime UUIDs/opaque refs are supplied by fixtures, not hardcoded into expected prose.

Critical conversations include:
1. Create map, speed history, speed value, doors value/history and next stop; name
   the dashboard `Мой любимый трамвай`, with and without quotation marks.
2. Move only the three current-value cards to the top in one row.
3. Rename the door card, remove an unwanted widget and request70% smaller cards.
4. Hide all headers and map metadata/notices; retain required attribution.
5. Request door history as a graph while keeping its separate current value.
6. Inject a technical preparation rejection, retry and retain name/pending work.
7. Undo, branch from the restored review, reload and continue.
8. Encounter genuine source ambiguity or unavailable data without inventing readings.

Additional cases cover text-only history, zero/false values, stale/missing data,
two sources with the same field label, unsupported settings, source replacement,
access revocation, empty output, duplicate requests and concurrent refinements.

## Execution and scoring
Each trial starts fresh. Follow actual plan IDs through POST plans, POST
clarifications and GET recovery. History selection uses the stored ancestor as
the next clarification base. A failed step does not disappear from the denominator;
record follow-up steps as failed or not reached. Never substitute mocked model
output for a required real-provider run.

Prefer code-based outcome checks: exact name, root fields, stable widget identity,
source/field semantics, requested presentation, allowed layout differences and
absence of unintended mutations. Compare normalized definitions by stable keys,
not provider prose or one mandatory tool-call trajectory. Allow valid alternative
layouts when the request leaves them open. Judge visual readability separately.

Report first-try and after-repair completion, whole-conversation success, success
across all repeated trials, repeated-known-field questions, no-op acceptance,
unintended changes, repair calls, latency and tokens/cost when actually available.
Unknown usage is null, never zero. Report infrastructure/provider failures as
separate categories while retaining them in overall availability results.

## Gates
- Baseline stage: runner self-tests detect bad outcomes and missing prerequisites;
  a real current-model baseline records known failures without changing the corpus.
- Final architecture release: all eight critical sequences pass five fresh trials
  each; zero unauthorized mutations, lost explicit names, silent no-ops or changes
  outside the permitted scope. Across the complete nonambiguous corpus, at least95%
  of trials fulfill all mandatory outcomes. Genuine ambiguity has its own expected
  clarification checks, not an automatic success label.
- Deterministic contract/security regressions must all pass. Required browser
  acceptance must pass at desktop and mobile widths. Test run size, distribution
  and confidence limitations remain explicit;40 critical trials are not a
  statistical guarantee of production reliability.

The gate values are project engineering decisions informed by evaluation practice,
not vendor performance guarantees. Compare another configured model on the frozen
corpus if quality remains poor; do not silently change the user's selected model.

## Evidence and safety
Use dedicated local accounts and deterministic synthetic observations for repeatable
tests, plus a separately labelled real HSL integration smoke. Live HSL availability
must not determine deterministic fixture correctness. Record fixture hash, commit/
dirty-tree fingerprint, versions, model/provider IDs, trial IDs, plan/review IDs,
outcome diffs, actual attempts and screenshots under ignored `artifacts/`.
Do not store JWTs, passwords, source/provider secrets or private reasoning. Run
planning-only by default; exercise confirmed execution in a separate isolated job,
compare the created draft with the exact review, then archive owned test resources.

## Basis
Apply outcome-based, repeated evaluations from
[Anthropic](https://www.anthropic.com/research/demystifying-evals-for-ai-agents)
and task-specific evaluation/tracing guidance from
[OpenAI](https://developers.openai.com/api/docs/guides/evaluation-best-practices).
They motivate the method, not the project's numerical thresholds.
