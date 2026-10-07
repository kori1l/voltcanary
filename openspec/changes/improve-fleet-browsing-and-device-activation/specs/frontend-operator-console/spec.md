## ADDED Requirements

### Requirement: Bounded fleet search and operational pagination

Fleet search SHALL debounce or explicitly apply input, cancel obsolete requests, reuse independent references and bound signal concurrency; incident and maintenance pages SHALL expose continuation beyond their initial limits.

#### Scenario: Rapid search input

- **GIVEN** inventory search and signal enrichment are active
- **WHEN** a complete word is entered quickly
- **THEN** one debounced search is applied; older requests cannot replace it and reference APIs are not reloaded for every character.

#### Scenario: Later operational records

- **GIVEN** over two hundred maintenance operations exist
- **WHEN** the user requests continuation
- **THEN** later operations are reachable without a guessed UUID and an unknown total remains labeled unknown.
