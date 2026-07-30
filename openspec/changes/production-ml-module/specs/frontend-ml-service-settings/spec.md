## MODIFIED Requirements

### Requirement: ML settings page exposes editable prediction preferences

The ML frontend SHALL evolve from simple prediction preferences into an
operational ML module that exposes dataset preparation, training, model registry,
deployment policy, and monitoring workflows while preserving simple account and
device prediction preferences.

#### Scenario: Operator reviews ML module state

- **GIVEN** an authenticated operator opens the ML area
- **WHEN** model registry, dataset, training, and deployment data are available
- **THEN** the UI shows current active model policy, fallback status, recent
  prediction health, dataset readiness, recent training runs, and model quality
  signals

#### Scenario: Operator imports external training data

- **GIVEN** an operator has an external dataset file
- **WHEN** the operator uploads the file and maps columns
- **THEN** the UI shows validation progress, rejected row reasons, accepted row
  counts, and the resulting dataset version readiness

#### Scenario: Operator promotes a model

- **GIVEN** a candidate model has evaluation metrics
- **WHEN** an authorized operator compares it with the active model
- **THEN** the UI allows approval, shadow deployment, canary deployment, active
  promotion, or rejection according to permissions

