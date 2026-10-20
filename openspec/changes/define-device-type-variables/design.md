# Определения переменных типов устройств: design

## Context

Use the [shared target architecture](../../../docs/target/device-variables.md)
for ownership, schemas, failure semantics and rollout. This document owns this
change's implementation decisions; its delta spec is the acceptance contract.

## Decisions
Device-service owns VariableDefinition, VariableRevision, TypeVariableDefault,
SourceVariableBinding and immutable SourceProcessingBundle. They are separate
application use cases; existing TelemetryTemplates must not become a catch-all
formula service. Catalogue ownership is not duplicated in telemetry.

Account + type scopes definitions. UUID identity and immutable key/type/unit are
separate from editable labels. Built-in keys/internal var_ namespace cannot be
overwritten. Formula edits create immutable revisions; unit/type change requires
a new identity. V1 numeric formulas reference only observed same-message fields.

Publish is a persisted revision operation. Activating a source is explicit and
pins a revision; changing the default never silently repins existing bindings.
New type members inherit defaults, but source role/input ambiguity yields UNBOUND.
No per-device coefficient overrides in v1; a different coefficient is a different
revision/binding choice. Bound-source override takes precedence over type default.

Binding and source input-policy updates share a device transaction and outbox.
Removing a retained dependency returns 409. Rollout over existing sources is
resumable/bounded with partial success visible; it is not one fleet transaction.

## API and persistence
Use the device-owned endpoints in architecture section 8. Mutations use expected
version, current resource authorization, immutable revision hash and actor audit.
Publish validates AST/unit contracts using the versioned language contract;
telemetry preview/runtime must pass identical contract fixtures before activation.
Archive retains historical revisions and produces disabled bundle generations.
Add portable migrations, unique account/type/key constraints and restrictive
foreign keys; no cascade deleting referenced revisions.

## Alternatives and risks
Owning definitions in telemetry would split device/type assignment policy.
Global type metadata must not make tenant-authored formulas global. Bulk defaults
must not grant access to all sources of a shared type.

## Delivery gate

First implementation stage of the device variables program.
All tasks remain open until implemented with evidence. Update current contracts
only for delivered behavior. Do not archive dependent placeholders as complete.
