## ADDED Requirements

### Requirement: Stage-four context and diagnostic preservation
The assistant SHALL preserve pending dashboard task intent after preparation failure,
constrain strict semantic data references to the current authorized capability
projection, and retain safe failure evidence for recorded scenario replays.

#### Scenario: A name answer follows a failed dashboard preparation
- **WHEN** dashboard preparation fails and the next user message supplies a name
- **THEN** the answer continues the dashboard task rather than proposing device creation.

#### Scenario: Strict response generation uses current data choices
- **WHEN** the semantic compiler requests a strict model response
- **THEN** data references are constrained to the current offered choices
- **AND** initial composition does not offer operations requiring an existing review.

#### Scenario: Provider output is incomplete
- **WHEN** a provider response reports incomplete output
- **THEN** safe response status and available usage counters are retained with the turn trace
- **AND** no private reasoning or raw provider body is persisted.
