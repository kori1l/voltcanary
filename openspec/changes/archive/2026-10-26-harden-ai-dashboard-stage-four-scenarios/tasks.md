## 1. Contract corrections
- [x] 1.1 Preserve failed dashboard task context for short replies; add regression evidence for the observed wrong-tool transition.
- [x] 1.2 Restrict strict response schemas to current data references and applicable initial operations; retain compiler/legacy behavior.
- [x] 1.3 Add safe provider-response usage/status diagnostics and regression checks without changing retry policy.
- [x] 1.4 Prioritize genuine unresolved source selection over generated view faults; verify the repeated-source-question scenario without unnecessary repair calls.

## 2. Scenario acceptance
- [x] 2.1 Replay original and compound edit, keep/undo, title, source and history scenarios using durable conversations; collect root-cause evidence for every failure.
- [x] 2.2 Verify browser desktop/mobile preview, reload and exact reviewed-versus-created configuration using isolated test resources.
- [x] 2.3 Run affected backend/frontend/harness checks, build, structure and strict OpenSpec validation; document measured results and deferred stage5/6 work.

## Evidence and explicit limitations

Recorded2026-10-26 in docs/current/flows/ai-creation-verification.md and ignored
artifacts/stage-four-scenario-report-20261026.md. Baseline1/2 dialogues,2/5 turns;
corrected12-case sample9/12,24/28; separate hint sample0/4,5/9. Historical scores
unchanged. Final source selection1/1,2/2 and confirmed compound creation43/43 pass.
Shared preview1100/420px has48px cards without clipping; the ordinary editor still
clips values by3.5px/4px overflow. This is a measured stage5 visual failure, not
successful editor acceptance. Remaining interpretation/preservation failures and
the derived-period grader mask are explicitly handed to stage5; stage6 repair
budgets and default rollout are deferred. Own test draft archived/device
decommissioned and temporary HTML removed. AI165 incl PostgreSQL, frontend410,
typecheck/build, harness37, structure and strict OpenSpec pass.
