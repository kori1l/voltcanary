# Change Proposal: Normalize Configuration Use Cases

## Why

The audit found a recurring naming and boundary problem: runtime configuration,
operator-managed settings, device policies, simulator YAML, and AI model
selection are all represented as `*ConfigService` or `*SettingsService` classes
inside service packages.

Concrete examples:

- `backend/services/analytics-service/.../service/MlSettingsService.java`
- `backend/services/device-service/.../service/DeviceAnalyticsConfigService.java`
- `backend/services/simulator-service/.../service/SimulationConfigService.java`
- `backend/services/ai-agent-service/.../service/AiModelConfigService.java`

Some of these classes are small today, but the naming invites future growth in
the wrong direction. Runtime properties belong in `config` packages as typed
configuration. Persisted operator choices should be modelled as policy or
use-case services with explicit ownership, validation, default resolution, and
mapping boundaries.

## What Changes

- Establish a repository-wide code organization spec for configuration and
  service-size guardrails.
- Rename or split user-managed configuration services into policy/use-case
  services without changing public HTTP contracts.
- Keep Spring `@ConfigurationProperties` classes and bean wiring in `config`
  packages.
- Extract default resolution, validation, DTO mapping, and persistence behavior
  when a configuration-like service starts combining them.
- Extend the repeatable structure check so future oversized services and
  configuration-service anti-patterns are visible before they become the local
  convention.
- Update project skills so future Codex work does not add generic
  `*ConfigService` or grow service/page/module monoliths.

## Non-Goals

- Do not remove existing runtime properties.
- Do not change endpoint paths, request/response DTOs, or persisted schema in
  this change unless a later implementation task proves a schema cleanup is
  required.
- Do not force tiny CRUD services into unnecessary abstraction layers.
- Do not replace product behavior with a naming-only refactor.

## Impacted Areas

- `openspec/specs/code-organization/spec.md`
- `scripts/check-project-structure.ps1`
- `.codex/skills/voltcanary-backend/SKILL.md`
- `.codex/skills/voltcanary-frontend/SKILL.md`
- `.codex/skills/voltcanary-ml-service/SKILL.md`
- `backend/services/*/src/main/java/**/service/*ConfigService.java`
- `backend/services/*/src/main/java/**/service/*SettingsService.java`
- Targeted backend tests for renamed or split services

## Open Questions

- Should the structure check fail the build for oversized classes immediately,
  or start as a report-only warning until the current known offenders are
  refactored?
- Should existing persisted resource names keep `config` in DTO and route names
  for API compatibility while Java service names move to policy/use-case terms?
