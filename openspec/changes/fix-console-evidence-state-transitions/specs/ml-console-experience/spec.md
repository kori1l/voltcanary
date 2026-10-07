## ADDED Requirements

### Requirement: Unavailable prerequisites are not missing objects

The ML pipeline SHALL preserve loading, unavailable and stale prerequisite evidence independently from an established missing prerequisite; remediation SHALL NOT advise creating an object solely because its inventory request failed.

#### Scenario: Dataset inventory unavailable

- **GIVEN** no authoritative dataset inventory was returned
- **WHEN** the dataset request fails
- **THEN** the pipeline reports unable to verify rather than create a dataset; retry targets the dataset resource only.
