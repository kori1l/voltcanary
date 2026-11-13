# Complete reviewed real-device setup

## Why
Device creation currently stops at an empty inventory card even when the user
supplies a supported real integration. Clarifications can also lose explicit
device names and reject a single device after repeated answers.

## What Changes
- Preserve explicit device choices across clarification without sharing dashboard state.
- Execute reviewed device creation and supported source provisioning through a dedicated executor.
- Add an owner-authorized HSL adapter with durable bounded bindings and live reload.
- Report source readiness separately from resource creation and retain replay safety.
- Keep existing dashboard compilation, authorization and confirmation contracts.

## Impact
AI planning/execution, optional local HSL connector, result UI and operating docs.
No arbitrary broker URLs, shell execution, credentials in model context or implicit simulation.
