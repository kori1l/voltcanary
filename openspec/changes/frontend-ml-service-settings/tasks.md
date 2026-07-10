## 1. Contract And Data Model

- [ ] 1.1 Define ML settings DTOs for account defaults and device overrides.
- [ ] 1.2 Define allowed prediction mode values: `MODEL_ONLY`, `MODEL_OR_BASELINE_HEURISTIC`, and `RULE_BASED_ONLY`.
- [ ] 1.3 Define maintenance ML behavior values: `SUPPRESS_ML` and `REDUCE_CONFIDENCE`.
- [ ] 1.4 Define validation rules for confidence warning threshold and incompatible settings.
- [ ] 1.5 Add persistence for account-level ML settings and device-level overrides.

## 2. Backend API Support

- [ ] 2.1 Add API to read effective ML settings for the current account.
- [ ] 2.2 Add API to update account-level ML settings.
- [ ] 2.3 Add API to read effective ML settings for a device, including inherited versus overridden source.
- [ ] 2.4 Add API to create, update, and clear device-level ML settings overrides.
- [ ] 2.5 Ensure authorization rules prevent users without write access from changing ML settings.
- [ ] 2.6 Ensure public API errors do not expose internal ML-service URLs or stack traces.

## 3. Analytics Integration

- [ ] 3.1 Load effective ML settings during analytics recalculation.
- [ ] 3.2 Skip ML inference and continue rule-based analytics when effective mode is `RULE_BASED_ONLY`.
- [ ] 3.3 Send model preference metadata when baseline heuristic output is disallowed.
- [ ] 3.4 Apply configured maintenance ML behavior when `excludeFromMl=true` windows overlap prediction input.
- [ ] 3.5 Persist prediction status that explains disabled, fallback, unavailable, insufficient-data, and maintenance-suppressed states.

## 4. Frontend API Client

- [ ] 4.1 Add typed frontend DTOs for ML settings, effective setting source, model metadata, and prediction status.
- [ ] 4.2 Add frontend API functions for reading and updating account ML settings.
- [ ] 4.3 Add frontend API functions for reading, saving, and clearing device ML settings overrides.
- [ ] 4.4 Reuse latest prediction metadata API for read-only ML status display.
- [ ] 4.5 Normalize API error responses into user-safe frontend error states.

## 5. Frontend Settings Experience

- [ ] 5.1 Add an authenticated ML settings route or settings panel in the existing app navigation.
- [ ] 5.2 Build account-level ML settings form with enablement, prediction mode, confidence threshold, maintenance behavior, and explanation visibility controls.
- [ ] 5.3 Build device-level override UI that shows inherited defaults and allows clearing overrides.
- [ ] 5.4 Build read-only ML status panel with model name, model version, model type, model status, prediction status, confidence, signals, explanations, recommended actions, and input hash.
- [ ] 5.5 Add degraded, unavailable, insufficient-data, and maintenance-suppressed UI states.
- [ ] 5.6 Ensure settings forms remain usable when latest prediction metadata is unavailable.

## 6. Documentation

- [ ] 6.1 Update `docs/api/analytics-api.md` with ML settings request/response contracts and prediction status semantics.
- [ ] 6.2 Update `docs/api/ml-api.md` with frontend-facing read-only model metadata notes.
- [ ] 6.3 Update `docs/flows/system-verification-flow.md` with ML settings verification scenarios.

## 7. Tests

- [ ] 7.1 Add backend tests for ML settings validation and authorization.
- [ ] 7.2 Add backend tests for account defaults and device override resolution.
- [ ] 7.3 Add analytics-service tests for rule-based-only mode, baseline-disallowed mode, and maintenance suppression behavior.
- [ ] 7.4 Add frontend tests for account settings form validation and save behavior.
- [ ] 7.5 Add frontend tests for inherited device defaults, device overrides, and clearing overrides.
- [ ] 7.6 Add frontend tests for ML-ready, unavailable, insufficient-data, and maintenance-suppressed status displays.

## 8. Verification

- [ ] 8.1 Run OpenSpec apply for `frontend-ml-service-settings` before implementation.
- [ ] 8.2 Run targeted backend tests for ML settings APIs and analytics integration.
- [ ] 8.3 Run frontend verification: `npm ci`, `npx tsc --noEmit`, and `npm run build` in `frontend`.
- [ ] 8.4 Run frontend test suite or targeted component tests for the ML settings workflow.
- [ ] 8.5 Run `npm run openspec:validate` before final response.
- [ ] 8.6 Record manual verification gaps for UI browser testing and end-to-end simulator scenarios.
