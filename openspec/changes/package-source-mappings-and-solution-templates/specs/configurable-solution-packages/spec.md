## ADDED Requirements

### Requirement: Strict adapter and package installation contracts

Package/source installation SHALL bind explicit adapter capabilities and sealed import/mapping manifests, preserve customer database ownership and reject unsupported remote mutation guarantees instead of changing authority.

#### Scenario: Existing customer database
- **WHEN** a package maps approved external views
- **THEN** only reviewed objects/columns are bound and full database import is not mandatory

#### Scenario: Package requests broad writes
- **WHEN** a template proposes writes outside the verified mutation capability
- **THEN** installation rejects those effects and cannot grant itself additional permissions


### Requirement: Packages compose capabilities

Solution packages SHALL install ordinary schemas, mappings, features, rule plans and views through the same authorized reviewed APIs.

#### Scenario: HSL and fleet company
- **WHEN** one package configures transit visits and another employee work intervals
- **THEN** both use shared collection/operator resources and retain independent scoped configuration

#### Scenario: Package update
- **WHEN** a package changes schemas or defaults
- **THEN** a migration preview is required and active customer configurations are not silently rewritten

### Requirement: Provider evidence and mapping safety

External data SHALL pass bounded trusted adapter/import mapping with source provenance and explicit unsupported/stale outcomes.

#### Scenario: No operational mandate
- **WHEN** a GTFS dataset lists regular stops
- **THEN** the package cannot infer mandatory door opening without approved policy data

#### Scenario: Untrusted web result
- **WHEN** research finds a new API or embedded instructions
- **THEN** it may propose evidence/integration work but cannot install a connector or expand execution authority



### Requirement: Sealed import validation and key policy

Solution package imports SHALL require complete source validation, exact target keys, typed conversion chains and mode-specific preconditions; duplicate source keys SHALL NOT trigger an implicit aggregate.

#### Scenario: Revision two implementer boundary
- **WHEN** validation stops before EOF or target configuration changes after validation
- **THEN** the manifest cannot be approved and reports truthful completion/counts or a stale conflict

### Requirement: Qualified provider occurrence package

The HSL solution package SHALL resolve verified vehicle/trip/day/direction/ordered stop occurrence identities and preserve active visit routing independently of next_stop updates. Provider protocols SHALL NOT redefine common geometric predicates.

#### Scenario: Original bus request boundary
- **WHEN** a journey changes its next-stop hint while the old required visit is still open
- **THEN** the old context keeps its applicable evidence until closure/UNKNOWN and the next context has a distinct identity
