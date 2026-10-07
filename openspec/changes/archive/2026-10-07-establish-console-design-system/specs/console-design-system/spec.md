## ADDED Requirements

### Requirement: Readable entity selection and advanced configuration

Console forms SHALL use searchable entity selection with readable names while
submitting canonical identifiers. Technical IDs SHALL remain inspectable and
copyable; JSON/YAML configuration SHALL be secondary to typed supported fields
in ordinary workflows without dropping unknown supported configuration keys.

#### Scenario: An operator chooses an entity without a UUID

- **GIVEN** authorized entities are available through existing typed APIs
- **WHEN** an operator selects a device, metric, dataset, snapshot, model or policy
- **THEN** selection SHALL show its readable identity and relevant status
- **AND** submitted identifiers SHALL retain their canonical server meaning
- **AND** unavailable entities SHALL be explained without fabricated options.

### Requirement: Responsive containment

The console SHALL contain cards, controls and actions within their allocated width, stack columns when necessary, and restrict horizontal scrolling to explicitly bounded tables or timelines.

#### Scenario: A narrow or zoomed console is opened

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the viewport is 360, 390, 768, 1024, 1280 or 1440 pixels, or text is zoomed to 200%
- **THEN** no page-level horizontal scroll, overlapping controls or clipped primary action occurs; wide datasets expose a bounded local scroll region.

### Requirement: Consistent named controls

All console forms SHALL use shared field, select, textarea, checkbox, switch and button conventions with permanent labels, accessible names, required-state indicators, units and local error messages. Typical text controls SHALL be 40–44 pixels high and checkboxes SHALL not stretch with grid cells.

#### Scenario: An operator edits a populated form

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** placeholder examples disappear after entry
- **THEN** each field remains identifiable and uses consistent focus, error, busy and disabled states without losing its value.

### Requirement: Predictable navigation and action hierarchy

Pages SHALL use a common header/context/actions layout with one primary action per independent task block, exactly one active leaf navigation item, independently scrollable desktop navigation and compact mobile navigation.

#### Scenario: A nested page is selected after scrolling

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator navigates to ML Settings or AI Settings
- **THEN** only the leaf uses the current-page highlight, the new main page begins at its header, and mobile navigation leaves the work area usable.

### Requirement: Accessible dialogs and guarded drafts

Dialogs and drawers SHALL use opaque surfaces, a stable header and footer, independently scrolling bodies, focus containment, Escape dismissal, focus restoration and unsaved-input protection. Consequential actions SHALL use one named confirmation dialog with object, impact, dependencies and a reason when required instead of chained browser prompts.

#### Scenario: A long dirty form or consequential action is opened

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator scrolls, cancels or requests closure
- **THEN** the footer remains reachable, keyboard focus stays within the panel, and unsaved input or action consequences are explained before it is discarded or applied.

### Requirement: Readable status and time presentation

The console SHALL use semantic status tokens with text equivalents, restrained neutral surfaces, readable hierarchy and consistent locale formatting. Time-dependent controls SHALL identify their timezone and distinguish it from display locale.

#### Scenario: An operator reads status and a time window

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** a maintenance interval, chart range or quiet-hours policy is displayed
- **THEN** status meaning is conveyed by text as well as color, the timezone is visible and dates, units and labels remain readable.
