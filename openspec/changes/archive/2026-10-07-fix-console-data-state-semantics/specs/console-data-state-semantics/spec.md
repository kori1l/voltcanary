## ADDED Requirements

### Requirement: Explicit independently loaded states

Data-backed console sections SHALL distinguish loading, successful-empty, filtered-empty, error, stale retained data and insufficient evidence. Failed dependencies SHALL not replace independently loaded sections with invented zeros.

#### Scenario: One dashboard dependency fails

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** devices load successfully but alerts fail
- **THEN** the device inventory remains visible, alert KPIs show unavailable/stale status with retry and freshness, and no all-clear conclusion is implied.

### Requirement: Unknown is not healthy or zero

Missing telemetry, risk, prediction and event coverage SHALL render as neutral unknown or insufficient-evidence states rather than healthy color, zero risk, zero percentage or a zero-valued chart.

#### Scenario: No samples or predictions exist

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator opens overview, analytics or ML monitoring
- **THEN** the page explains the missing evidence and next step; graphs and KPI cards do not fabricate measurements.

### Requirement: Freshness and retry preserve operator context

Refreshing or retrying SHALL preserve valid filters, drafts, selected entities and last successful data while marking its freshness. A successful empty result SHALL explain first-use versus no matching filters with the appropriate action.

#### Scenario: A previously loaded section refresh fails

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** the operator retries the failed request
- **THEN** prior evidence remains labeled stale with its timestamp and selections intact until a newer valid result is received.

### Requirement: Useful fleet dashboard

The dashboard SHALL prioritize actionable devices and alerts with freshness, evidence and a direct next action; registry ACTIVE SHALL not by itself imply an online telemetry connection.

#### Scenario: A registered device has no recent telemetry

- **GIVEN** the relevant console workflow is available to the authorized operator
- **WHEN** fleet health is summarized
- **THEN** registration status and connection freshness remain separate and the operator can navigate from an issue to the relevant device or alert.

