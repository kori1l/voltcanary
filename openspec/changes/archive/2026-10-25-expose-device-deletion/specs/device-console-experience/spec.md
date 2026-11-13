## ADDED Requirements
### Requirement: Discoverable device deletion
The console SHALL offer an explicit Delete device action with confirmation naming
the target and offering an explicit keep/delete-history policy. Irreversible
deletion SHALL be distinct from retirement and SHALL use an owner-scoped durable
request. The interface SHALL show incomplete cleanup truthfully.
#### Scenario: Delete an old bus
- **WHEN** the owner confirms deletion from device details
- **THEN** the bus becomes permanently inaccessible as an active registry device and its sources stop accepting data; history follows the confirmed policy
#### Scenario: Cancel or rejected deletion
- **WHEN** the user cancels or the domain API rejects deletion
- **THEN** the device remains visible and an error is shown for the rejected request

### Requirement: Resumable history disposition
Deletion SHALL preserve owner-readable history when requested, otherwise purge
owned history and prevent late events from recreating it. Cleanup SHALL be
idempotent and survive unavailable participants without reporting completion.
#### Scenario: Delayed service
- **WHEN** a history owner is unavailable during deletion
- **THEN** the receipt remains pending and resumes cleanup without restoring the device
#### Scenario: Replayed reading after purge
- **WHEN** an old reading is delivered after history deletion
- **THEN** the deleted device history is not recreated
