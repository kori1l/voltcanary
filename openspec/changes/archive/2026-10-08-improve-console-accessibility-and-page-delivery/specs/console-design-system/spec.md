## ADDED Requirements

### Requirement: Accessible tabs and nested operational controls

Tab interfaces SHALL expose selected state and panel relationships with keyboard navigation, and operational containers SHALL NOT intercept activation intended for nested action controls.

#### Scenario: ML keyboard selection

- **GIVEN** all eight ML sections are available
- **WHEN** the user enters the tab interface and presses arrows and Tab
- **THEN** the selected tab is announced and the intended panel receives navigation.

#### Scenario: Agenda cancellation

- **GIVEN** an agenda row contains Open and Cancel commands
- **WHEN** Enter activates Cancel
- **THEN** only Cancel is requested and the row does not independently open.

### Requirement: Semantic mobile controls and feedback

Console controls SHALL retain inline labels and required markers, semantic status colors and accessible feedback; mobile inventory SHALL expose name, freshness, risk and one primary next action without document horizontal overflow.

#### Scenario: Unknown KPI and populated mobile inventory

- **GIVEN** risk is unavailable and long device names exist
- **WHEN** the console is viewed at 390 pixels or 200 percent text
- **THEN** unknown is neutral, labels remain readable and the primary action is reachable.
