## ADDED Requirements

### Requirement: Verification of configured bindings and actual effects

Verification of new configured workflows SHALL compare original goals to exact dataset/field/feature/source/rule bindings, reviewed digests, initial owner receipts and readiness evidence, independent of writer masks or valid VCL syntax.

#### Scenario: Right chart wrong field
- **WHEN** a valid candidate binds a different customer field or external object
- **THEN** verification rejects the unmet goal despite structural validity

#### Scenario: Later data changed
- **WHEN** the underlying external/native data changes after configuration was saved
- **THEN** verification distinguishes original configuration equality from current observation values



### Requirement: Verified dashboard candidate acceptance
A new candidate SHALL become READY only after application verification checks its prepared definition against the selected base, retained mandatory goals, original-request target evidence and compiler-derived allowed changes; writer-selected masks alone SHALL NOT establish correct interpretation, and a failed compound candidate SHALL be rejected atomically.

#### Scenario: Correct format but wrong edit
- **WHEN** a syntactically valid candidate changes a chart while the retained goal requests moving current-value cards
- **THEN** the candidate is rejected, the goal remains pending and the last valid preview remains available

#### Scenario: Unexpected binding change
- **WHEN** a title-only operation changes a source binding or query in the prepared result
- **THEN** verification rejects it before persistence as READY

#### Scenario: Necessary top-row reflow
- **WHEN** inserting the requested top card group shifts existing panels down without changing their identity, settings or relative order
- **THEN** declared valid reflow passes while unrelated reorder or data changes fail

#### Scenario: Writer and assessor share a wrong target
- **WHEN** a valid candidate and its writer-selected mask both describe the wrong widget
- **THEN** writer self-consistency is not independent proof; final verification uses original request/base evidence and independently calibrated criteria, with unresolved mandatory criteria preventing acceptance

### Requirement: Verified no-op disposition and truthful summaries
The system SHALL distinguish a provably already-satisfied goal from empty or ineffective model output, and SHALL derive successful change summaries from verified differences rather than model claims.

#### Scenario: Header already hidden
- **WHEN** the requested header is already hidden and that property is verified in the selected base
- **THEN** the response reports ALREADY_SATISFIED while retaining the same immutable review and creating no duplicate executable action

#### Scenario: Pending failed move receives empty output
- **WHEN** the model returns no effective edits while a move goal remains unsatisfied
- **THEN** the response cannot claim completion and the prior review is retained

### Requirement: Faithful readable preview acceptance
Visual acceptance SHALL exercise the shared ordinary renderer at compact desktop/mobile sizes with explicit evidence states, and SHALL remain separate from deterministic configuration correctness.

#### Scenario: Compact stale card
- **WHEN** a one-row card shows a retained stale observation
- **THEN** the primary value and concise uncertainty indicator remain readable and full provenance is available through panel information

#### Scenario: Review is executed
- **WHEN** a verified accepted review is confirmed through ordinary execution
- **THEN** the created draft has the exact reviewed configuration and digest-compatible preparation without promising telemetry values remain frozen
