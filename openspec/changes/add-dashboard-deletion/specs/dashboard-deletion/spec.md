## ADDED Requirements

### Requirement: Generic resource lifecycle preservation

Dashboard deletion SHALL preserve independently owned datasets, connections, spatial features, compiled rules and their evidence; deleting a view SHALL NOT imply deleting its data sources or stopping automations.

#### Scenario: Configured dashboard deleted
- **WHEN** an owner deletes a dashboard that displays configured external/native data and rule evidence
- **THEN** only the authorized dashboard lifecycle changes and independent resources remain governed by their own owners


### Requirement: Owner dashboard deletion
The system SHALL allow only the current owner in the owning account to delete a dashboard
with the saved draft precondition, without deleting devices or telemetry.
#### Scenario: Confirmed deletion
- **WHEN** the owner confirms deletion with the current saved draft ETag
- **THEN** the dashboard disappears from all catalogues and direct access, while immutable audit/history remain internally
#### Scenario: Foreign or stale request
- **WHEN** another owner requests deletion or the draft token is stale
- **THEN** deletion is rejected without changing the dashboard
### Requirement: Explicit deletion interaction
The console SHALL offer deletion in the shared management controls with explicit confirmation.
#### Scenario: Failed deletion
- **WHEN** deletion fails
- **THEN** the confirmation remains visible with an error and the dashboard is retained
