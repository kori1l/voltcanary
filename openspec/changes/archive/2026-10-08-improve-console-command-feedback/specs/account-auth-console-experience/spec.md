## ADDED Requirements

### Requirement: Recovery guidance matches configured delivery

Recovery screens SHALL explain available configured delivery and token fallback without asserting an email was delivered when that result is unknown.

#### Scenario: Delivery unavailable

- **GIVEN** a reset request uses a disabled or unconfirmed delivery configuration
- **WHEN** a response is displayed
- **THEN** the user receives accurate next steps without fabricated delivery success or disclosure of account existence.
