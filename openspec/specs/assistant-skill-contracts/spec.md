# assistant-skill-contracts Specification

## Purpose
TBD - created by archiving change define-ai-assistant-skill-contracts. Update Purpose after archive.
## Requirements
### Requirement: Explicit dialogue contract before runtime migration
Canonical planner and step contracts SHALL carry current raw user input and relevant prior dialogue as separate data, including exact pending questions/choices, server-assigned question identity, focus, chronological resource events and explicit history omissions. Reproducible contract checks SHALL be stored in the repository and SHALL NOT require ignored local validation artifacts.

#### Scenario: Short reply contract
- **WHEN** a step request carries a short reply to an earlier question
- **THEN** its shape can carry both the actual earlier question and current raw reply, while missing required dialogue or current message fails contract validation

#### Scenario: Completed plan resource contract
- **WHEN** a new plan refers to a resource created by a previous plan
- **THEN** context can carry original message links, ordered resource events and receipt-backed identity independently of the previous plan being active

### Requirement: Versioned server-owned skill contracts
The assistant SHALL expose only authorized registered intents to model planning and SHALL load reviewed versioned skills with typed input, output, context-loader and command contracts.

#### Scenario: Compact catalogue
- **WHEN** a planner request is assembled
- **THEN** only bounded intent summaries are included; unrelated full skills are not loaded

#### Scenario: Unknown skill
- **WHEN** model output names an unregistered intent or schema
- **THEN** the proposal is rejected before any tool execution

### Requirement: Proposal and authority separation
The assistant SHALL treat model responses as untrusted typed proposals and SHALL retain server ownership of identity, access, confirmation, verification and receipt-backed completion.

#### Scenario: Invented success
- **WHEN** a model claims a command already succeeded
- **THEN** no executed state or resource fact is established without a domain receipt

#### Scenario: Parameter suggestions
- **WHEN** a permitted parameter is absent
- **THEN** the model may propose a value under that field contract without making an optional parameter mandatory

