## ADDED Requirements

### Requirement: Semantic operation contract corrections
The opt-in semantic operation protocol SHALL represent keeping the exact selected
review, same-turn widget renaming, authorized cross-device replacement and
distinct history views without requiring raw model definitions.

#### Scenario: Keep selected review exactly
- **WHEN** a user requests no changes to an authorized READY review
- **THEN** exclusive keep_review reuses its exact saved definition and preparation payload after current authorization checks
- **AND** initial creation and mixed edits are rejected while outstanding earlier goals retain their pending dispositions without being applied or superseded

#### Scenario: Name a newly created widget
- **WHEN** rename_widget references an earlier show/add operation by @id
- **THEN** the server renames only that resolved widget and rejects unknown or forward references

#### Scenario: Replace data with another authorized device
- **WHEN** set_data selects compatible data from another current authorized device
- **THEN** preparation includes that device in dashboard scope and preserves unrelated widgets and existing device scope

#### Scenario: Preserve a distinct history view
- **WHEN** show_history requests a different view without an explicit target
- **THEN** it creates or reuses that view without converting a different existing view
- **AND** an explicit existing target can request a compatible view change

#### Scenario: Bound provider arguments
- **WHEN** a provider supports strict structured output
- **THEN** schema enumerations and ranges constrain placement, periods, views and sizes consistently with server validation

#### Scenario: Duplicate legacy catalogues do not exhaust refinement budget
- **WHEN** an opt-in multi-device review is refined using authoritative data choices
- **THEN** unused templates and duplicate legacy field/metric summaries are removed before the unchanged input budget is enforced
- **AND** current data choices, selected widget state and credential scanning remain intact
