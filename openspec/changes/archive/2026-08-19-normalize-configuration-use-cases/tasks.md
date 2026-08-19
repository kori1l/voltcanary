# Tasks: Normalize Configuration Use Cases

## 1. Guardrail Contract

- [x] 1.1 Add `code-organization` OpenSpec requirements for runtime configuration, policy/settings use cases, oversized units, and reviewable boundaries.
- [x] 1.2 Update project skills with explicit rules against new generic `*ConfigService` or `*SettingsService` growth.
- [x] 1.3 Decide whether oversized-unit detection starts as report-only or fail-fast.

## 2. Backend Configuration-Like Services

- [x] 2.1 Audit `MlSettingsService`, `DeviceAnalyticsConfigService`, `SimulationConfigService`, and `AiModelConfigService` for runtime configuration vs persisted policy responsibilities.
- [x] 2.2 Rename or wrap persisted user-choice services with business names such as policy, preferences, model selection, or simulator profile use cases.
- [x] 2.3 Extract validation/default-resolution/mapping helpers where the same service currently owns persistence and representation logic.
- [x] 2.4 Preserve existing route paths and DTO compatibility.

## 3. Repeatable Checks

- [x] 3.1 Extend `scripts/check-project-structure.ps1` to report oversized production service/page/module files and risky `*ConfigService`/`*SettingsService` patterns.
- [x] 3.2 Add fixture coverage or a documented manual check for the structure script.
- [x] 3.3 Wire the structure check into the narrowest appropriate validation command after the current known offenders are remediated.

## 4. Verification

- [x] 4.1 Run affected backend unit tests for renamed or split services.
- [x] 4.2 Run `powershell -ExecutionPolicy Bypass -File scripts/check-project-structure.ps1`.
- [x] 4.3 Run `npm run openspec:validate`.
