# Improve ML setup, data preparation, training and operations

## Why

ML forms overlap adjacent columns, lifecycle status dominates the page without remediation links, import requires mapping JSON and absent predictions are represented by zero percentages.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: F26, F27, F28, F29, F30, F31, F32, F33, F34. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Actionable ML pipeline and scope: ML SHALL provide a collapsible mode-specific pipeline with readable prerequisite state and remediation links, named account/device selectors and explicit inheritance. Confidence warnings SHALL be explained as thresholds rather than prediction accuracy.
- Preview-bound dataset preparation: Workbench SHALL order scope, metrics, period, readiness and commit, explain blocking reasons and use only backend-preview metric availability. Any relevant draft change SHALL invalidate preview and keep creation disabled until a matching fresh result exists.
- Readable dataset snapshot and import workflows: Dataset/snapshot detail SHALL prioritize quality, readiness and compatibility before IDs/hashes/lineage. Import SHALL provide bounded CSV/JSONL file preview, visual column mapping, validation and accepted/rejected results with raw mapping JSON advanced.
- Auditable labels and generation: Labels SHALL use named devices/metrics, readable outcome labels, interval evidence and reasoned correction with accessible audit history. Horizon generation SHALL display dataset scope, horizon, progress and final counts while preventing duplicate jobs.
- Guided contained training: Training SHALL stay within responsive columns and guide dataset, compatible snapshot, algorithm and review with actionable prerequisites, explicit horizons/gates and visible job progress and retry/cancel consequences.
- Reviewed model and deployment decisions: Model registry SHALL prioritize family, horizon, state and evaluation gates, with artifact and lineage detail secondary. Policy editing SHALL select scope, purpose and compatible model, explain Shadow/Canary/Active/fallback and show effective policy before applying valid configuration.
- Evidence-backed ML monitoring: Monitoring SHALL display freshness, observation window and denominators beside rates, render unavailable prediction evidence as insufficient-data rather than 0%, and explain serving path, fallback and confidence.

## Impact

- Added capability: `ml-console-experience`.
- Implementation owners: frontend/src/features/ml-settings/, frontend/src/features/ml-datasets/, focused ML import/label/model/policy modules, typed analytics clients.
- Prerequisites: `establish-console-design-system`, `fix-console-data-state-semantics`.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.


## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
