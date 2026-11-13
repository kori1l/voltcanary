## ADDED Requirements

### Requirement: Vehicle movement view is minimal and honest
VEHICLE details SHALL provide a focused Movement tab with one selected source, default 15m history and 15m/1h/24h choices. It SHALL reuse a shared map renderer displaying latest accepted position, course marker, bounded trail, speed, observation time and freshness. A single source SHALL be selected automatically; multiple sources SHALL require an explicit selection. Start/Stop SHALL remain in the existing Simulation workflow.

#### Scenario: First position
- **WHEN** the selected automobile source emits its first accepted point
- **THEN** the map fits and shows its marker and textual position without drawing a fabricated route.

#### Scenario: Source absent or no evidence
- **WHEN** no compatible source or no accepted point exists
- **THEN** the UI offers simulation setup or an explicit waiting/no-data state and does not center a vehicle at (0,0).

#### Scenario: Gap and restart jump
- **WHEN** adjacent accepted points have a gap above 30s or imply a speed above 300 km/h
- **THEN** the renderer breaks the trail instead of presenting the missing transition as observed continuous travel.

### Requirement: Map renderer preserves camera, evidence and accessibility
The shared MapLibre renderer SHALL use GeoJSON [longitude,latitude], retain its instance across evidence refreshes, resize to its container and dispose listeners/resources on unmount. Follow SHALL control automatic camera tracking and manual pan SHALL disable it. Stale evidence SHALL NOT be extrapolated. Textual position/speed/time and labeled keyboard controls SHALL remain available, with attribution and explicit style/tiles/WebGL failure independent of telemetry failure.

#### Scenario: Follow and resizing
- **WHEN** the user pans manually or resizes a dashboard panel
- **THEN** Follow stops until enabled again and map geometry fits the new container without resetting the selected source or period.

#### Scenario: Unavailable basemap
- **WHEN** tiles, style or WebGL fail
- **THEN** the UI reports map unavailability with Retry while authorized textual coordinates, speed and timestamps remain available.

#### Scenario: Stale position and unknown speed
- **WHEN** evidence becomes stale or speed is null
- **THEN** the marker stays at the last accepted position with explicit age/state and null speed is not displayed as measured zero.

### Requirement: Vehicle map is a typed single-device dashboard widget
The dashboard catalog SHALL expose vehicle-map typeVersion 1 and POSITIONS evidence for exactly one fixed VEHICLE device/source binding in the legacy sourceId mode, without scalar reducers or incompatible chart modes. It SHALL reuse the existing palette, configuration, query composition, common renderer, layout, copy, draft and publication workflows. Binding SHALL remain intersected with current runtime filters and resource access. A history range above 24h SHALL produce an isolated typed limit error. The separately accepted hsl-live-vehicles requirement owns the explicit vehicleSources static fleet mode; this baseline SHALL preserve its compatibility.

#### Scenario: Save and reload map
- **WHEN** a user configures, resizes, saves, publishes, copies or reloads a vehicle map
- **THEN** selected device/source, initial Follow presentation, screen and layout survive the applicable existing workflow.

#### Scenario: Unsupported binding or representation
- **WHEN** dynamic binding, multiple vehicles with only a single sourceId, a non-vehicle device or a scalar reducer/pie representation is supplied
- **THEN** validation rejects or explains the incompatible configuration without aggregating coordinates.

#### Scenario: Runtime filter excludes vehicle
- **WHEN** dashboard scope filters exclude the fixed automobile
- **THEN** its panel reports empty scope without returning hidden position evidence.

### Requirement: Refresh is bounded and context safe
The standalone Movement view SHALL poll at 5s only while active and visible, maintain one in-flight view request, use a captured common asOf and cancel/ignore obsolete source/range requests. Dashboard maps SHALL use the existing common refresh/query/cache mechanism with a 5s live-demo option and SHALL NOT start renderer-local data timers. Inactive screens SHALL perform no GPS reads or allocate active map resources.

#### Scenario: Changed source during request
- **WHEN** an older response returns after device/source/time context changed
- **THEN** it cannot replace evidence of the new selection or contextHash.

#### Scenario: Screen hidden or component removed
- **WHEN** the Movement tab, browser document or dashboard screen becomes inactive
- **THEN** its position polling/reads stop and unmounted map resources are released.

#### Scenario: New sample under live refresh
- **GIVEN** a 5s dashboard refresh and a newly accepted GPS point
- **WHEN** the next successful refresh completes
- **THEN** bounded cache policy allows the new point to appear without a parallel widget timer or coordinate prediction.
