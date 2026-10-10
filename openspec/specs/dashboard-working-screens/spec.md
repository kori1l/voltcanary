# dashboard-working-screens Specification

## Purpose
Define named working screens with independent widget sets, explicit relative placement and active-screen evidence reads.
## Requirements
### Requirement: Named task screens preserve independent contents

An authorized private dashboard SHALL support named Monitoring, Analytics,
Incidents or user-defined screens with distinct widget sets and accessible
navigation through the compact working-screen selector. Users SHALL choose each
screen's name and widget set without mandatory Overview or Analytics screens.
Legacy definitions SHALL retain their existing contents on one
screen. Screen labels SHALL NOT grant authorization roles or resource access.

#### Scenario: Separate working screens

- **GIVEN** a user creates Monitoring, Analytics and Incidents screens
- **WHEN** they configure different widgets, publish and reload
- **THEN** screen navigation retains each set and its layout, reads only the active screen, and preserves the immutable publication until another explicit publish.

#### Scenario: Legacy definition and removed screen

- **GIVEN** an existing single-screen dashboard or a screen containing widgets
- **WHEN** the legacy definition is loaded or the user removes the screen while preserving widgets
- **THEN** no widget disappears implicitly and undo restores the previous arrangement.

### Requirement: Relative placement expresses the intended location

The editor SHALL add palette widgets at explicit grid positions and move a
widget above, below, left or right of an anchor when responsive bounds allow. The chosen placement SHALL be pinned,
colliding neighbours displaced deterministically, and unrelated screens and
mobile/desktop placements preserved. Keyboard and pointer SHALL have equivalent
commands, meaningful focus and undo.

#### Scenario: Add to the right

- **GIVEN** the anchor and new widget fit the desktop row
- **WHEN** the user drops a palette widget to the right of the anchor and configures it
- **THEN** it appears immediately right of the anchor; conflicting neighbours move safely and other screens retain their layouts.

#### Scenario: Narrow screen cannot fit a side placement

- **GIVEN** a mobile or narrow layout lacks the required columns
- **WHEN** placement commands are shown
- **THEN** side placement explains its unavailability and above/below commands remain usable without overlapping widgets.

### Requirement: Working panels emphasize recorded business evidence

Screens SHALL display readable recorded values, units, charts and tables, keep
technical metadata in disclosures, distinguish zero/unknown/no-data/stale and
break chart continuity across recorded gaps. Publication SHALL validate all
screen widgets without silently querying inactive screen facts.

#### Scenario: Graphs and unavailable measurements

- **GIVEN** a screen contains recorded series with gaps and a missing measurement
- **WHEN** the screen is viewed or switched by keyboard
- **THEN** measured segments remain distinct, missing values do not become zero, and only that active screen can request its authorized evidence.
