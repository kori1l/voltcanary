# Implementation and verification

- [x] 1.1 Implement versioned routing profiles, explicit PINNED/AUTO and allowlisted provider/model configurations without changing stored credentials or user model settings.
- [x] 1.2 Build complete-request size estimates including actual dialogue/pending questions/resource events, instructions/schemas and output/reasoning reserve; persist EXACT/ESTIMATED and route reason. Preserve mandatory conversational context rather than truncating it to force a cheaper model.
- [x] 1.3 Implement atomic turn and parent-plan call/token/time/cost reservations before every actual provider call, including failures and uncertain responses.
- [x] 1.4 Normalize native tools, strict JSON and JSON-text proposals through the same canonical validator; forbid hidden adapter retries.
- [x] 1.5 Implement versioned price snapshots, currency rules, unknown-price policy and correct cached/reasoning accounting without double counting.
- [x] 1.6 Integrate cancellation, restart, duplicate request, concurrent reservations and explicitly authorized escalation while preserving completed mutations.
- [x] 1.7 Emit MODEL_CALL and budget/usage events into the shared trace; use actual calls rather than model-run summaries for accounting.
- [x] 1.8 Test exhaustion, missing usage/prices, unsupported output capabilities, no affordable candidate, pinned mode and two-account isolation offline.
- [x] 1.9 Align routing/settings API and operations docs during implementation; run affected Java/frontend checks and strict repository validation.

## Evidence — 2026-10-26

Implemented the A2 internal boundary: `ModelRouting`, `RoutedModelCalls`,
`BudgetedModelCalls`, provider proposal normalization, existing scoped provider
availability and `JdbcAttemptLedger` with additive V007 migration. Policies and
prices use the canonical typed A1 records. No new HTTP writer, production skill
package, qualification claim or legacy runtime switch is introduced; A3/A4/A5
supply orchestration, context and domain integration as assigned in the index.

Command: `cd backend; mvn -pl services/ai-agent-service -am
-Dtest=AssistantProtocolScenarioTest,AssistantRoutingBudgetScenarioTest
-Dsurefire.failIfNoSpecifiedTests=false test` — 2 scenarios passed, 0 failures,
0 skipped. Contract scenario 0.772 s; routing/budget scenario 0.663 s on the final
recorded run. Scenario two covers final-body sizing, pinned capacity rejection,
qualification/access/output-mode/affordability rejection, unknown price policy,
cached/reasoning accounting, envelope normalization, real JDBC reservations,
restart/delivery, concurrent repair reservations, parent and input limits,
account isolation, unknown usage/transport, cancellation/late response, deadline,
trace-start failure and application-boundary replay. H2 runs the actual V007 SQL;
the existing V004 fixture needs a TIMESTAMPTZ compatibility domain.

`npm run structure:check` passed with existing risk warnings;
`npm run openspec:validate` passed 86/86. Per the user's limit, no full Maven suite,
Docker/PostgreSQL integration, browser campaign or live provider calls ran.
Deployment qualification and live portability/cost evidence remain A6 gates;
legacy NL removal remains A5. Existing settings, reviews, receipts and history
were not rewritten. No AGENTS/skill update is required by these internal seams.

## Archive disposition — 2026-10-28

All tasks in this internal-boundary scope are complete with recorded offline
evidence. Archived through the standard validated OpenSpec command with main
spec updates. Historical downstream deferrals are owned by A3 task1.9, A5 and A6;
they do not reopen this bounded milestone or establish full model qualification.
