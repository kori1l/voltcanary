# Implementation and verification

- [x] 1.1 Define typed canonical contracts and generate schemas/provider projections without model-specific domain fields; include current message/messageId, actual prior dialogue, pending question/choices/identity, focus, ordered resource events, history completeness/cursor and replyToQuestionId.
- [x] 1.2 Implement versioned skill registry, parameter requirements/proposal policies, command schemas and capability-filtered intent summaries.
- [x] 1.3 Define trust boundaries, technical format validation and rejection of model-provided authority, verification or execution claims.
- [x] 1.4 Inventory all supported legacy tool scenarios and semantic regex/keyword/question-text decisions; assign replacement owners and parity fixtures.
- [x] 1.5 Add reproducible repository-owned positive/negative contract fixtures, version compatibility and unknown intent/skill/schema rejection before provider or domain calls; cover short replies to actual questions, missing dialogue/current message and cross-plan resource references. CI validation must not depend on ignored local artifacts.
- [x] 1.6 Keep legacy conversation/review readers and exact reviewed execution; document opt-in writer negotiation and rollback.
- [x] 1.7 Align target/current documentation as implementation lands; run narrow contract tests, structure and strict OpenSpec validation.

## Evidence — 2026-10-26

Implemented `orchestration.contract` (typed wire records, canonical validator,
transitive provider projections and deployment-owned skill registry). The current
AI API documents negotiation/rollback and the legacy replacement/parity inventory.
One offline scenario, `AssistantProtocolScenarioTest`, validates all 30 agreed
examples with Java round trips and adversarial versions/dialogue/authority/registry
checks. Maven targeted test passed; `npm run structure:check` passed with existing
risk warnings; `npm run openspec:validate` passed 86/86. No live calls, A3/A4
runtime, production domain packages or default enable. A1 is contract acceptance,
not semantic model qualification or completion of A5 legacy parity.

## Archive disposition — 2026-10-28

All tasks in this internal-boundary scope are complete with recorded offline
evidence. Archived through the standard validated OpenSpec command with main
spec updates. Historical downstream deferrals are owned by A3 task1.9, A5 and A6;
they do not reopen this bounded milestone or establish full model qualification.
