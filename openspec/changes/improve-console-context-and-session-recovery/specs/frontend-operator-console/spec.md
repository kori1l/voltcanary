## ADDED Requirements

### Requirement: Canonical route context and guarded workflow drafts

ML, inventory, alerts and maintenance SHALL encode safe navigable context in typed query parameters and protect dirty drafts across internal route and tab transitions.

#### Scenario: Reload and Back

- **GIVEN** a user selected an ML tab or maintenance range
- **WHEN** the page reloads or browser Back is used
- **THEN** the intended context is restored without publishing draft state.

#### Scenario: Dirty route form

- **GIVEN** a notification or rule form has unsaved changes
- **WHEN** a different route or tab is requested
- **THEN** Keep preserves the draft, Discard is explicit, and any persisted recovery excludes secrets and is scoped to the same user and account.
