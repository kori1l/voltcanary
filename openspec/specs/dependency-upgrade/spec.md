# Dependency Upgrade

## Purpose

Define dependency upgrade policy, toolchain baselines, reproducible installation rules, and verification gates for VoltCanary.

## Requirements

### Requirement: Unified Toolchain Baselines

The project SHALL define consistent Java, Node, Python, and OpenSpec toolchain baselines across local metadata, CI, Docker Compose, and Kubernetes manifests.

#### Scenario: Node baseline is consistent

- **GIVEN** root OpenSpec tooling and frontend build tooling both require Node
- **WHEN** dependency upgrade work is complete
- **THEN** root `package.json`, `.node-version`, `.nvmrc`, `frontend/package.json`, CI, and docs SHALL describe the same Node major-version baseline
- **AND** no project file SHALL advertise support for Node 14.

#### Scenario: Java and Python baselines are documented

- **GIVEN** backend uses Java and ML-service uses Python
- **WHEN** dependency upgrade work is complete
- **THEN** CI, Docker images, and README setup instructions SHALL agree on the supported Java and Python versions.

### Requirement: Pre-Production Upgrade Policy

Dependency upgrades SHALL prefer a clean current stack over backward compatibility with old local environments.

#### Scenario: A major upgrade requires source changes

- **GIVEN** a selected current stable dependency version introduces breaking API changes
- **WHEN** the project is upgraded
- **THEN** application code and tests SHALL be updated to the new API
- **AND** old compatibility shims SHALL NOT be kept unless they are explicitly justified in the implementation notes.

### Requirement: Reproducible Dependency Installation

The project SHALL use reproducible dependency installation for JavaScript and Python surfaces.

#### Scenario: Frontend install is reproducible

- **GIVEN** frontend dependencies are upgraded
- **WHEN** `npm ci` runs in `frontend`
- **THEN** install SHALL use `frontend/package-lock.json`
- **AND** the lockfile SHALL match `frontend/package.json`.

#### Scenario: ML-service install is reproducible

- **GIVEN** ML-service dependencies are upgraded
- **WHEN** a developer or CI installs ML-service dependencies
- **THEN** dependency versions SHALL be pinned directly or resolved through a committed constraints/lock file
- **AND** `requirements.txt` and `pyproject.toml` SHALL not disagree about the supported dependency set.

### Requirement: Backend Dependency Compatibility

Backend dependency upgrades SHALL preserve a compatible Spring ecosystem set.

#### Scenario: Spring versions are upgraded

- **GIVEN** Spring Boot or Spring Cloud is upgraded
- **WHEN** backend dependency versions are selected
- **THEN** Spring Boot and Spring Cloud SHALL be selected as a compatible pair
- **AND** dependency versions managed by the Spring Boot BOM SHOULD NOT be overridden without justification.

### Requirement: Explicit Runtime Image Tags

Runtime image upgrades SHALL avoid floating tags for project-critical services.

#### Scenario: Compose and Kubernetes use infrastructure images

- **GIVEN** Docker Compose or Kubernetes manifests reference infrastructure images
- **WHEN** dependency upgrade work is complete
- **THEN** project-critical services SHOULD use explicit stable image tags
- **AND** any remaining floating tag SHALL be documented with a reason.

### Requirement: Verification Gates

Dependency upgrade work SHALL not be considered complete until all relevant verification gates pass or are explicitly recorded as skipped.

#### Scenario: OpenSpec validation passes

- **WHEN** `npm run openspec:validate` is executed
- **THEN** all active OpenSpec changes SHALL validate successfully.

#### Scenario: Frontend verification passes

- **WHEN** frontend dependencies are changed
- **THEN** `npm ci`, `npx tsc --noEmit`, and `npm run build` SHALL pass in `frontend`.

#### Scenario: ML-service verification passes

- **WHEN** ML-service dependencies are changed
- **THEN** dependency installation and `python -m unittest discover -s app/tests` SHALL pass in `ml-service`.

#### Scenario: Backend verification passes

- **WHEN** backend dependencies are changed
- **THEN** `mvn test` SHALL pass from `backend`
- **AND** any failing tests caused by behavior changes SHALL be fixed or intentionally rewritten to reflect desired new behavior.

#### Scenario: Deployment config remains parseable

- **WHEN** container images or deployment manifests are changed
- **THEN** `docker compose config` SHALL pass
- **AND** Kubernetes/Helm manifests touched by the change SHALL remain syntactically valid.
