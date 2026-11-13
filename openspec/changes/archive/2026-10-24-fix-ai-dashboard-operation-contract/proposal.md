# Stage4 operation contract corrections

## Why
Review of stage4 exposed gaps in unchanged-review handling, same-turn widget
titles, cross-device binding replacement and history view reuse. These are
compiler/contract defects, independent of the stage5 outcome verifier and stage6
repair coordinator. The archived stage4 evidence remains immutable.

## What Changes
- Add exclusive keep_review with authorized exact saved-review reuse.
- Permit bounded earlier operation references for widget renaming in creation.
- Reconcile authorized dashboard device scope when replacing widget data.
- Reuse history only for the same view; explicit targets request view changes.
- Constrain bounded operation arguments in provider JSON Schema.
- Add regressions, Docker validation and separate repeated model evidence.

## Impact
Opt-in dashboard-operations-v1 only; existing stored reviews remain readable and
executable. Default stays disabled. No stage5/6 implementation or release claim.

Prerequisite: archived compile-ai-dashboard-semantic-edits (stage4).
