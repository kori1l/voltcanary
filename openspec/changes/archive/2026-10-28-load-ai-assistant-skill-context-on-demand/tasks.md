# Implementation and verification

- [x] 1.1 Implement reviewed/versioned skill loading and separate planner versus step context projections with explicit manifests and instruction hashes.
- [x] 1.2 Build typed loaders over ordinary scoped device/source/dashboard APIs, sharing existing widget/data capabilities rather than duplicating matrices.
- [x] 1.3 Project all parameter rules/provenance/proposal permissions plus current raw message, relevant prior messages and exact pending question/choices; remove code-based regex name/period/type extraction and question-wording interpretation, while requiring model interpretation of dialogue.
- [x] 1.4 Add context snapshot/asOf/completeness, dialogue omissions/cursors, mandatory conversational kernel, focus/resource events, technical depth/item/size limits and model-compatible token estimates; never hide competing resource candidates under truncation.
- [x] 1.5 Implement bounded NEED_CONTEXT paging and REPLAN_REQUIRED with registered loaders/scoped cursors; preserve the original message and charge every model continuation/replan to the shared budget.
- [x] 1.6 Handle empty/stale/deleted/unavailable data truthfully; fail or ask to narrow when mandatory content cannot fit rather than silently dropping it.
- [x] 1.7 Test minimal-context sufficiency, prompt-injection separation, no invented fields, optional facts, cursor misuse and budget exhaustion offline.
- [x] 1.8 Align context/skill contracts and diagnostics during implementation; run relevant Java/contract tests, structure and strict OpenSpec validation.

## Evidence and integration boundary — 2026-10-26

Implemented in `orchestration/context/`, the scoped JDBC/domain/review adapters,
`RoutedIntentModels` and portable Flyway V009. Canonical step requests expose
parameter/schema/loader contracts separately from untrusted loaded data; old A1
requests remain readable. All 30 canonical examples round-trip in the narrow
context scenario. Reviewed skill versions/digests and parameter contracts are
checked before routing. Mandatory dialogue, original raw input, exact answered
question/choices, reviewed commands, receipt facts and competing resource events
are preserved; only optional neighbours may be dropped with explicit omissions.
Domain capability/widget rules come from the ordinary dashboard APIs.

Verification command from `backend/`:

`mvn -pl services/ai-agent-service -am -Dtest=IntentPlanningSafetyScenarioTest,SkillContextScenarioTest -Dsurefire.failIfNoSpecifiedTests=false test -q`

Two offline JUnit scenarios pass. They use actual H2 SQL/JDBC migrations,
history/cursors/reviews/receipts and A2 ledger; provider responses and scoped
HTTP transport are simulated. Checks cover original-message/question preservation
across reload, parameter proposal permissions, history paging and stable upper
sequence, altered/cross-scope cursors, unknown loaders/arguments, mandatory size
failure, credential exclusion, instruction/data separation, truthful empty,
deleted and dependency-failed reads, two confirmed devices in a subsequent plan,
shared-budget continuation exhaustion, REPLAN_REQUIRED, review supersession,
request deduplication and stale-result cancellation. The two-device projection
also verifies typed confirmation/control events in the existing transcript.
These fixtures are not evidence of real model language interpretation quality.

`npm.cmd run structure:check` passed with the existing unrelated risk warnings;
`npm.cmd run openspec:validate` passed strict 86/86. Scoped whitespace checks passed.
No frontend source changed. Full service regression, PostgreSQL/browser acceptance,
paid/live provider tests and default enable remain deferred under the user's
limited-check instruction. V009 was exercised on H2; PostgreSQL acceptance remains
open alongside A3 task 1.9. A4 is not archived pending downstream integration.

Task 1.3 applies to the new context path: it performs no semantic regex extraction
or interpretation of question wording. Removing the preserved legacy generation
path belongs to A5's exhaustive parity migration; legacy confirmation/readers stay
usable. A5 still supplies deployment-owned domain skill packages, transport role
serialization, compilers and UI wiring. Existing verifier/recovery owners retain
their offline prerequisites before A5. Generation and release gates stay off.

AGENTS.md/project skills require no update: module boundaries, validation commands
and agent workflow are unchanged; the implemented contracts are documented in the
current AI API and canonical target documents.

## Archive disposition — 2026-10-28

All tasks in this internal-boundary scope are complete with recorded offline
evidence. Archived through the standard validated OpenSpec command with main
spec updates. Historical downstream deferrals are owned by A3 task1.9, A5 and A6;
they do not reopen this bounded milestone or establish full model qualification.
