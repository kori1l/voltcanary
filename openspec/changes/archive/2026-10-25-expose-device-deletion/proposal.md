# Delete devices with an explicit history policy

## Why
The existing DELETE /devices/{id} only retires a device. The user explicitly
requires irreversible removal, separately from Decommission, and a choice to
retain or purge its historical measurements, analytics and events.

## What Changes
- Add an explicit Delete device action in the upper-right device header.
- Add an owned durable deletion request with KEEP/DELETE history policy.
- Irreversibly hide deleted devices from ordinary registry APIs, revoke sources,
  stop integrations and coordinate idempotent cleanup through data-owning services.
- Retain owner-readable history when requested; otherwise purge history and fence
  late/replayed writes. Show pending cleanup rather than claiming early completion.
- Keep Decommission and the legacy DELETE semantics backward compatible.

## Impact
Device, telemetry, analytics, alert and simulator lifecycle, connector cleanup,
console deletion/history UI and documentation. Dashboard definitions are preserved;
deleted resources cease to be valid active bindings. Shared ML artifacts are not
silently deleted; purge must report a dependency blocker where necessary.
