## 1. Versioned corpus and harness
- [x] 1.1 Inventory existing fixtures/scripts and record which cover creation, refinement, restoration, execution and rendering; retain template compatibility coverage.
- [x] 1.2 Add the versioned corpus/schema with at least24 scenarios, eight critical conversations, per-turn outcomes and held-out paraphrases.
- [x] 1.3 Implement the authenticated real-path runner, fresh fixture isolation, repeat counts and separate planning/execution jobs.
- [x] 1.4 Implement code-based outcome graders and self-tests proving detection of wrong names, wrong bindings, unintended changes, no-ops and skipped provider calls.
- [x] 1.5 Record per-turn/trial/conversation results, model/prompt/schema hashes, first failure, actual repair counts and sanitized evidence.

## 2. Baseline and release protocol
- [x] 2.1 Run the current configured model on the frozen corpus; preserve known failures as baseline and verify teardown/recovery using ordinary APIs.
- [x] 2.2 Record browser baseline at desktop/mobile widths and distinguish fixture-based repeatability from real HSL integration availability.
- [x] 2.3 Document the final release gates and model-comparison procedure in the existing AI verification flow and reviewed-task runbook.
- [x] 2.4 Run changed runner/Java/frontend boundary checks as applicable, repository/OpenSpec checks and review evidence for credentials; do not mark model quality accepted from harness completion.
