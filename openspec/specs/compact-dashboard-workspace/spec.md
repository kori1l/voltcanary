# compact-dashboard-workspace Specification

## Purpose
Keep visualization space primary through a compact header, right settings drawer and optional left navigation.
## Requirements
### Requirement: Canvas-first dashboard workspace
The editor and viewer SHALL present a compact dashboard header and working-screen navigation before the canvas. Repeated explanations, screen headings and settings forms SHALL NOT occupy permanent space above panels. Settings and secondary actions SHALL remain available through keyboard-accessible controls.
#### Scenario: Open a healthy desktop dashboard
- **WHEN** a populated dashboard opens at a desktop viewport
- **THEN** its panels begin within 220 CSS pixels of the workspace top, with dashboard name, primary actions and active screen available
#### Scenario: Open settings or exceptional state
- **WHEN** the user opens settings or encounters a conflict, recovery or failed read
- **THEN** settings use a focus-managed drawer on the right and exceptional states remain explicit without weakening save/publication safeguards
#### Scenario: Operate on mobile
- **WHEN** the workspace is opened at a narrow viewport
- **THEN** controls wrap without page overflow and screen selection, panel configuration and movement remain operable

### Requirement: Optional left navigation
The user SHALL be able to hide and restore left navigation using one accessible SVG toggle in the workspace header; on mobile it SHALL open a focus-managed left navigation drawer. Hiding it SHALL release its column to the canvas, and a restore control SHALL remain available across route changes.

#### Scenario: Hide and restore navigation
- **WHEN** the user toggles navigation visibility
- **THEN** the canvas uses the available width and the toggle exposes its expanded state and supports restoring navigation

