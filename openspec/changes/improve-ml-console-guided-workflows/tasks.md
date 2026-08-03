## 1. Workflow Structure

- [ ] 1.1 Add explicit anomaly-detection and failure-prediction workflow modes in ML Settings.
- [ ] 1.2 Show step status for dataset, labels, feature snapshot, training, approval, policy, and monitoring.
- [ ] 1.3 Split Labels into generated horizon labels and manual correction/review areas.
- [ ] 1.4 Ensure the UI never mixes anomaly-only prerequisites into failure-prediction training instructions.

## 2. Entity And Metric Selectors

- [ ] 2.1 Replace raw dataset device-id textarea with a searchable device multi-select.
- [ ] 2.2 Populate metrics from selected devices and support common-metric filtering.
- [ ] 2.3 Replace feature snapshot id text entry with a selector showing family, horizon, row count, hash, and id.
- [ ] 2.4 Replace policy device id entry with a device selector and device-type entry with a device-type selector.
- [ ] 2.5 Improve model selectors to show model family, algorithm, status, origin, version, horizon, and id.
- [ ] 2.6 Keep copyable ids available in compact secondary text or copy buttons.

## 3. Draft Persistence

- [ ] 3.1 Persist unsaved form drafts per account, tab, workflow mode, and form.
- [ ] 3.2 Restore drafts after tab changes and browser reloads.
- [ ] 3.3 Validate restored drafts against current backend entities.
- [ ] 3.4 Clear a draft after successful submit or explicit reset.

## 4. Failure Feature Snapshot UI

- [ ] 4.1 Add model family selection to feature snapshot generation.
- [ ] 4.2 Add horizon days when model family is `FAILURE_PREDICTION`.
- [ ] 4.3 Validate snapshot horizon against generated label horizon and training horizon.
- [ ] 4.4 Show latest generated snapshot metadata immediately after generation.

## 5. Model Cards And Approval Gates

- [ ] 5.1 Parse `evaluationMetricsJson` or expose typed `evaluationMetrics` from the API.
- [ ] 5.2 Update failure model cards to show label coverage, positives, negatives, PR-AUC, F1, false alerts, lead time, and load-test status.
- [ ] 5.3 Make frontend approval button state match backend approval behavior.
- [ ] 5.4 Show backend approval rejection reasons without raw JSON noise.

## 6. Validation, Layout, And Accessibility

- [ ] 6.1 Add client-side validation for date ranges, required entity selections, horizon mismatch, and impossible gates.
- [ ] 6.2 Use proper input controls for booleans, numbers, dates, option sets, and entity references.
- [ ] 6.3 Ensure long ids, hashes, and model names wrap/truncate without overlapping controls.
- [ ] 6.4 Add focused frontend tests for selectors, drafts, validation, and responsive layout.

## 7. Verification

- [ ] 7.1 Run frontend tests covering ML workflow forms.
- [ ] 7.2 Run the Docker failure-prediction smoke path through UI-assisted steps where possible.
- [ ] 7.3 Run `npm run openspec:validate`.
