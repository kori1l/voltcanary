## ADDED Requirements

### Requirement: Safe return routes and coordinated session recovery

Authentication recovery SHALL preserve a validated relative application route, coordinate simultaneous refresh attempts, and transition to expired-session login once when refresh is definitively rejected.

#### Scenario: Concurrent expiry

- **GIVEN** several independent reads return 401
- **WHEN** the access token must be refreshed
- **THEN** one refresh is performed, waiting reads share its outcome and successful login restores device metric interval context.

#### Scenario: Unsafe return destination

- **GIVEN** returnTo points to //external.example or an authentication loop
- **WHEN** login completes
- **THEN** the application ignores that destination and uses a safe internal default.
