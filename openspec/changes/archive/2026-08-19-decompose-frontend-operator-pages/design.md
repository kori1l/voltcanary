# Design: Decompose Frontend Operator Pages

## Current State

The frontend already has domain API modules and some feature components, but
the largest pages still own multiple workflows directly.

Observed boundary mixing:

- `DeviceDetailsPage.tsx` owns data fetching, polling, telemetry chart
  construction, maintenance commands, alert commands, registry commands,
  simulator YAML parsing/mutation, CSV import/export, localStorage preferences,
  and the SVG chart implementation.
- `MlSettingsPage.tsx` owns ML settings, dataset import, labels, async horizon
  jobs, training, models, deployment policies, monitoring, lifecycle
  confirmations, localStorage drafts, and many formatter helpers.
- Page tests necessarily mock broad API surfaces because workflows are not
  isolated behind hooks or focused components.

## Target Shape

Use pages as route shells that compose feature modules:

- Device details:
  - `useDeviceDetailsData`
  - `useDeviceTelemetryWorkspace`
  - `useDeviceMaintenanceActions`
  - `useDeviceRegistryActions`
  - `useDeviceSimulationWorkspace`
  - `DeviceTelemetryTab`, `DeviceAlertsTab`, `DeviceConfigurationTab`,
    `DeviceSimulationPanel`
  - chart helpers/components under `features/telemetry`
- ML Settings:
  - `useMlWorkspaceData`
  - `useMlLifecycleActions`
  - `useHorizonLabelGeneration`
  - `useTrainingDraft`
  - tab components under `features/ml-*`
  - formatting/model helpers under feature `model` folders
- Shared:
  - typed localStorage hooks for drafts and preferences;
  - reusable lifecycle confirmation helper or component;
  - page-level tests kept for critical flows only.

## Compatibility

Routes remain unchanged:

- `/devices/:deviceId`
- `/settings/ml`

Existing domain API modules remain the only place where network requests are
implemented. Extracted hooks can call API modules, but components should receive
data and command props where practical.

## Verification Strategy

- Unit tests for extracted hooks and pure model helpers.
- Existing page tests retained for critical operator flows.
- Frontend typecheck, test, and build must pass.
- Browser verification is required only when layout or chart extraction changes
  visible behavior.
