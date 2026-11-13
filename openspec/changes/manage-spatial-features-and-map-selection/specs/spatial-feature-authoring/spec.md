## ADDED Requirements

### Requirement: Specified geometry uncertainty and shared resource identity

Geometry methods SHALL use declared coordinate/shape/unit contracts and uncertainty-aware three-valued membership, preserving exact authorized feature revisions for UI, AI and runtime.

#### Scenario: Uncertain boundary
- **WHEN** position distance/accuracy straddles a zone boundary
- **THEN** membership is UNKNOWN and cannot establish a definitive enter/exit

#### Scenario: Same geometry different place
- **WHEN** two business locations share or overlap coordinates
- **THEN** selection retains actual record/feature identity rather than nearest-label substitution


### Requirement: Generic spatial resources

The platform SHALL create and revise scoped spatial features reusable by tables, assets and rules without a transit-specific dependency.

#### Scenario: Warehouse and stop
- **WHEN** a company selects a warehouse and another selects a stop
- **THEN** both use the same feature API and circle semantics

#### Scenario: Legacy circles
- **WHEN** an existing monitoring configuration is opened
- **THEN** its behavior remains compatible and canonical import requires explicit review

### Requirement: Shared map authoring

Users and AI SHALL use the same authorized geometry validation, revision and selection contracts, with map objects linked to exact record identities.

#### Scenario: Map selection
- **WHEN** a user selects a feature in the map or a linked table row
- **THEN** both views identify the same resource/revision

#### Scenario: Ambiguous click
- **WHEN** several places lie near the selected point
- **THEN** the user is offered actual choices instead of silently selecting the nearest business entity



### Requirement: Observed spatial exit with uncertainty

Spatial rule execution SHALL combine reported accuracy with the approved floor/kernel bound and require the configured actual exit observations and debounce; UI selection alone SHALL NOT assert a runtime exit.

#### Scenario: Revision two implementer boundary
- **WHEN** a vehicle emits no additional points after entering a zone
- **THEN** idle timeout reports unknown evidence rather than manufacturing an OUTSIDE observation
