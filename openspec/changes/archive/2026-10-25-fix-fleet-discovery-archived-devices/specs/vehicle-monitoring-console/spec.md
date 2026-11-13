## ADDED Requirements
### Requirement: Fleet discovery excludes archived inventory from the display cap
The console SHALL page authorized vehicle inventory and exclude DECOMMISSIONED
vehicles before applying its 100-vehicle display limit. It SHALL preserve source
authorization, show truncation when additional eligible vehicles exist, and
report discovery errors without presenting a partial list as complete.

#### Scenario: New real vehicle follows archived test devices
- **WHEN** more than 100 archived vehicles precede a newly created accessible vehicle
- **THEN** the new vehicle is discovered and its selected source can participate in the ordinary authorized fleet position batch

#### Scenario: Fleet exceeds the display limit
- **WHEN** more than 100 non-decommissioned vehicles are discovered
- **THEN** at most 100 are shown and the console displays its truncation indicator
