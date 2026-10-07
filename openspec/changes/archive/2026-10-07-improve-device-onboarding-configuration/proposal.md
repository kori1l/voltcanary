# Improve device inventory, onboarding, imports and configuration

## Why

Device cards are inefficient for a larger fleet, wizard state and partial failures are difficult to recover, CSV imports lack preview and configuration requires raw technical fields.

Source: [UI design audit](../../../../docs/target/ui-design-requirements.md).
Coverage: F07, F08, F09, F10, F16, F17. This is planned work; no implementation or verification is claimed complete.

## What Changes

- Scannable fleet inventory: Devices SHALL provide a compact fleet table alongside location/card views, with name, location, registry state, connection freshness and risk; search, filters and first-use states SHALL remain readable across widths.
- Recoverable four-step onboarding: The Basics, Location, Telemetry and Review wizard SHALL distinguish current/completed steps, retain draft state when going back, keep actions reachable and summarize source mode and simulator start behavior before creation.
- Clear location and source choices: Onboarding SHALL separate existing/new location selection, group optional building/floor/room/zone/tags fields, and explain source-specific required and advanced parameters.
- Previewable CSV imports: Device and source imports SHALL accept files with a downloadable template, supported-column and row preview, row-numbered validation and separate accepted/rejected results. Textarea entry SHALL remain an advanced alternative.
- Readable source sensor and analytics configuration: Configuration SHALL group source and sensor inventories with focused editors, named metric mapping and units, typed known analytics parameters and advanced JSON preserving unknown keys. Credential rotation/revocation SHALL explain the effect on ingestion.
- Preset-first simulator workspace: Demo Tools SHALL expose presets before advanced parameters, show units, disclose anomaly settings when enabled and distinguish draft, saved configuration and running state. Validate, Save, Start/Stop and run history SHALL remain accessible.

## Impact

- Added capability: `device-console-experience`.
- Implementation owners: frontend/src/pages/DevicesPage.tsx, device configuration and simulation feature modules, frontend/src/devices/ and frontend/src/simulation/.
- Prerequisites: `establish-console-design-system`, `fix-console-data-state-semantics`.
- Existing APIs, authorization and domain invariants remain authoritative. No breaking API, data migration, token-policy change or UI translation is proposed.
- Follow existing frontend-operator-console decomposition and typed API/storage rules. If implementation needs an additional API contract, document its exact compatible scope before changing it.
- Validation includes focused tests and browser evidence, frontend typecheck/test/build, OpenSpec validation and aligned current documentation. Unobserved audit states require populated fixtures.


## Completion

Implemented and archived on 2026-10-07. See [verification evidence](../../../../docs/current/flows/console-interface.md#validation-on-2026-10-06) and the completed task checklist. Original proposal context above records the planned scope.
