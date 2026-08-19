# Design: Normalize Configuration Use Cases

## Current Findings

The repository already separates many Spring runtime properties into `config`
packages, but user-managed configuration resources have drifted into generic
service names.

Examples:

- `MlSettingsService` resolves account defaults, device overrides, validation,
  DTO mapping, and persistence in one compact class.
- `DeviceAnalyticsConfigService` reads and writes device analytics JSON,
  performs mapping, publishes device registry events, and audits changes.
- `SimulationConfigService` validates YAML, templates defaults, persists active
  configs, and writes version history.
- `AiModelConfigService` manages AI model selection, default switching, provider
  ownership checks, and persistence.

The immediate issue is not only line count. The word `config` is overloaded:
sometimes it means environment/runtime settings, sometimes tenant policy,
sometimes simulator source material, and sometimes model-selection state.

## Target Shape

Use three distinct categories:

- Runtime configuration: immutable typed properties and bean wiring in `config`
  packages.
- Operator policy/settings: account, device, model, or simulator use-case
  services named for the business concept, not generic runtime configuration.
- Serialization and defaults: small helpers or mappers when validation,
  defaulting, JSON/YAML conversion, and persistence would otherwise collect in
  one service.

Public compatibility is preserved by keeping existing controllers, DTOs, and
route names unless a later product change explicitly replaces them.

## Structure Check Strategy

Extend `scripts/check-project-structure.ps1` in two stages:

1. Report production files that exceed agreed thresholds or combine risky
   markers, such as `@Service` plus `*ConfigService`, many constructor
   dependencies, wildcard imports, or many public use-case methods.
2. Turn the check into a failing gate once the current known offenders are
   split by the dedicated remediation changes.

Suggested initial thresholds:

- Java production service class over 350 lines requires explicit decomposition
  notes in the active OpenSpec change.
- Java service constructor over 8 injected collaborators requires a split or a
  documented facade.
- Frontend page over 600 lines requires feature components/hooks before adding
  workflow behavior.
- Python service module over 350 lines requires extraction of domain logic,
  adapters, or DTO assembly.

Thresholds are guardrails, not architecture by themselves. A small file can
still violate boundaries, and a generated or mechanical file can be exempted
only with a clear reason.

## Migration Notes

Prefer thin compatibility facades where controller churn would be noisy. For
example, a controller may keep injecting the old service name during a first
pass if the old class delegates to new, focused collaborators and the next pass
removes the facade.

Avoid schema or API changes in this refactor unless they are required to remove
ambiguous persisted state. This change is about implementation boundaries and
future guardrails.
