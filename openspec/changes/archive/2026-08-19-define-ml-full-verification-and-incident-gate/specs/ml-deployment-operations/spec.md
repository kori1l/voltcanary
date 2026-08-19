## ADDED Requirements

### Requirement: ML deployment verification covers incident outcomes

Deployment verification SHALL prove that active, shadow, canary, and fallback
policies produce the expected prediction and alert/incident behavior.

#### Scenario: Active high-risk deployment creates user-visible incident

- **GIVEN** an active deployment policy selects a compatible trained model
- **AND** that model produces high-risk output
- **WHEN** analytics and alert processing complete
- **THEN** latest prediction SHALL identify the active model and policy source
- **AND** alert-service SHALL create a user-visible `OPEN` alert unless alert
  suppression is configured.

#### Scenario: Shadow high-risk deployment does not create active incident

- **GIVEN** a shadow deployment produces high-risk output
- **WHEN** active prediction remains low-risk
- **THEN** the shadow result SHALL be recorded separately
- **AND** no user-visible active incident SHALL be created from the shadow
  result alone.

#### Scenario: Canary exclusion does not create model incident

- **GIVEN** a canary deployment excludes the device deterministically
- **WHEN** the candidate model would have produced high risk
- **THEN** the active prediction and incident outcome SHALL follow the current
  active or fallback path
- **AND** traceability SHALL record canary exclusion.

#### Scenario: Fallback path creates or suppresses incidents consistently

- **GIVEN** an active model is unavailable and fallback is enabled
- **WHEN** fallback produces high risk
- **THEN** alert behavior SHALL follow the fallback prediction trace
- **AND** the alert source SHALL not claim that the unavailable trained model
  produced the incident.
