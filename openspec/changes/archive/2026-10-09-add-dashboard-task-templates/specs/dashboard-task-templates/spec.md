## ADDED Requirements

### Requirement: Versioned task templates with symbolic binding

Templates SHALL encode versioned task and persona defaults with symbolic resource bindings, show capability gaps before creation, and copy into an independent private draft without updating published dashboards when the template changes.

#### Scenario: Cafe template lacks temperature

- **GIVEN** a user owns refrigeration devices with power but no temperatureC
- **WHEN** the refrigeration template is selected
- **THEN** missing sensors are explained, compatible power panels remain available, and no temperature is fabricated.

#### Scenario: Template upgrade after publication

- **GIVEN** a user published template version1
- **WHEN** version2 becomes available
- **THEN** their published revision stays unchanged; an explicit new draft or copy is required.

### Requirement: Business templates respect available contracts

Shift, My devices, Refrigeration and Technician templates SHALL use the specified task periods and evidence; Energy SHALL require verified energy aggregation, and persona selection SHALL NOT modify resource permissions.

#### Scenario: Technician without assignment API

- **GIVEN** a technician task template is selected
- **WHEN** configuration and preview occur
- **THEN** only authorized equipment is bound and a nonfunctional assignment filter is not offered.

#### Scenario: Energy capability not installed

- **GIVEN** the energy business task is selected
- **WHEN** the catalog is rendered
- **THEN** dependency readiness is explained rather than summing arbitrary counters or showing invented cost.
