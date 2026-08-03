## 1. Audit Reconciliation

- [x] 1.1 Build a status matrix for `docs/ml-failure-prediction-production-audit.md` mapping each major blocker to implemented evidence or remaining work.
- [x] 1.2 Link implemented items to archived OpenSpec changes or concrete tests where possible.
- [x] 1.3 Identify any remaining production blockers that are not covered by current specs.

## 2. Production Readiness Verification

- [x] 2.1 Define a repeatable Docker verification scenario for the complete failure prediction lifecycle.
- [x] 2.2 Verify the scenario covers dataset versions, feature snapshots, horizon labels, real artifacts, approval gates, deployment policy, live inference trace, and monitoring signals.
- [x] 2.3 Add or document commands so the scenario can be rerun without manual database intervention.

## 3. ML Settings Frontend Polish

- [x] 3.1 Replace cramped inline ML Settings controls with stable responsive form grids.
- [x] 3.2 Normalize ML Settings API error banners so raw JSON is not displayed.
- [x] 3.3 Fix mojibake separators and long identifier wrapping in labels, training runs, policies, and model cards.
- [x] 3.4 Add or update frontend tests for the affected ML Settings tabs.

## 4. Verification

- [x] 4.1 Run frontend typecheck.
- [x] 4.2 Run ML Settings frontend tests.
- [x] 4.3 Run `npm run openspec:validate`.
