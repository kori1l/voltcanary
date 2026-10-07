## ADDED Requirements

### Requirement: Route-scoped application delivery

The console SHALL load heavy route modules on demand and keep stable, actionable loading and chunk-error states without downloading ML or administration modules for initial authentication.

#### Scenario: Initial login and failed lazy chunk

- **GIVEN** the user opens login then navigates to ML
- **WHEN** a route chunk fails to load
- **THEN** login did not preload all heavy features and the ML route provides a contained retry while preserving safe route context.
