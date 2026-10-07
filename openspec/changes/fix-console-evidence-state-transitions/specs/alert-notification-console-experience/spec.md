## ADDED Requirements

### Requirement: Delivery attempt errors remain unknown

Delivery history SHALL retain a resource state per alert and SHALL report no attempts only following a successful empty history response.

#### Scenario: Delivery history fetch fails

- **GIVEN** an operator expands an alert history
- **WHEN** the request fails
- **THEN** that alert shows a local error and retry; no recorded-attempts absence is asserted.
