# dashboard-widget-queries Specification

## Purpose
Define typed authorized widget queries, honest aggregate evidence and bounded isolated reads.
## Requirements
### Requirement: Typed authorized query context

Widget queries SHALL use validated server manifests and an authorized saved revision or draft, intersect dashboard, runtime and widget scope with current resource access, and resolve one explicit time context with asOf and contextHash.

#### Scenario: Dynamic and fixed device bindings

- **GIVEN** a location filter changes and a fixed kitchen widget exists
- **WHEN** the dashboard is queried
- **THEN** dynamic panels use the new authorized location, fixed bindings retain their explicit override in panel information and filter settings, and no foreign UUID expands access; normal scope metadata does not require a permanent footer.

#### Scenario: Calendar DST and late response

- **GIVEN** Today is selected in Europe/Belgrade while an older context is in flight
- **WHEN** the query is resolved across DST or the old response returns
- **THEN** all inherited widgets share correct UTC calendar boundaries and obsolete contextHash results cannot replace the new context.

### Requirement: Full aggregate denominators and evidence semantics

Counts and reducers SHALL use complete authorized scope or explicitly partial coverage, distinguish snapshot from event interval, and preserve source units, quality, exclusions, freshness policy and prediction semantics.

#### Scenario: Fleet larger than current UI limit

- **GIVEN** 250 authorized devices exist
- **WHEN** fleet count and critical incident panels are queried
- **THEN** counts cover the full authorized set or explicitly report evaluated/requested coverage; notification attempts are not counted as incidents.

#### Scenario: Sparse cadence and unavailable predictions

- **GIVEN** a manual source has no recent sample and prediction data is unavailable
- **WHEN** the panel evaluates evidence
- **THEN** freshness follows its explicit cadence policy, unknown is not healthy zero, and confidence is not displayed as measured accuracy.

### Requirement: Independent read-only panel results and secure cache

Query composition SHALL isolate per-panel failures, enforce bounded fanout and cache partitioning by authorization and context, and SHALL NOT trigger recalculation, inference jobs, source rotation or notifications.

#### Scenario: One dependency fails

- **GIVEN** telemetry succeeds while analytics fails
- **WHEN** a batch refresh completes
- **THEN** telemetry remains visible and analytics is unavailable or labeled stale only under the same authorized context.

#### Scenario: Authorization changed before cache hit

- **GIVEN** cached data exists for a previously authorized viewer
- **WHEN** a grant or account is revoked before another request
- **THEN** renewed access checks prevent cached values and unauthorized entity names from being returned.

### Requirement: Manifest capabilities and measured query budgets

Catalog capabilities SHALL expose supported units, reducers and limits with at most 24 widgets,8series,500scope devices and50detail devices in v1; performance targets SHALL require measured evidence.

#### Scenario: Exceeded widget or scope limit

- **GIVEN** a definition exceeds24widgets or runtime scope exceeds500devices
- **WHEN** validation or query occurs
- **THEN** a typed limit error requests a narrower configuration instead of silent truncation.

#### Scenario: Unsupported metric or widget

- **GIVEN** temperature or a newer typeVersion is absent
- **WHEN** preview or normal query occurs
- **THEN** the missing capability is explained, unsupported data is not fabricated, and supported panels continue to work.

### Requirement: Understandable predictive evidence presentation
Prediction panels SHALL display stored failure risk as a percentage only for valid trained failure-prediction evidence, retain explicit unavailable states, show horizon and confidence distinct from measured accuracy, and permit authorized device drill-down. Optional model and observation-time columns and risk sorting SHALL be saved in widget presentation settings. Sorting SHALL disclose that only the returned bounded page is sorted when further rows exist.

#### Scenario: Analyst prioritizes a trained prediction
- **GIVEN** an authorized trained FAILURE_PREDICTION row has score 0.72, confidence 0.8 and horizon 7 days
- **WHEN** the analyst views the prediction panel
- **THEN** it displays 72% risk, seven days, 80% confidence with its distinct meaning, trained state and the authorized device link.

#### Scenario: Missing or heuristic evidence
- **GIVEN** a heuristic, unavailable or invalid prediction row
- **WHEN** the panel renders
- **THEN** it does not invent a zero failure probability or a trained model, and retains the recorded state and fallback reason.

#### Scenario: Saved presentation and page boundary
- **GIVEN** optional columns and risk ordering are configured and the response has further rows
- **WHEN** the saved widget renders
- **THEN** it honors those columns and ordering while disclosing the displayed page boundary.

### Requirement: Stored prediction model drill-down
Trained prediction states SHALL link to the exact response model version recorded in authorized evidence. ML workspace SHALL show the selected authorized model card or explicit unavailable state. Missing or invalid identity and heuristic states SHALL remain noninteractive labels. Navigation SHALL NOT trigger inference or deployment mutations.
#### Scenario: Actual trained model
- **GIVEN** a stored trained prediction with an actual response model UUID
- **WHEN** its trained model link is activated
- **THEN** ML workspace opens the model registry with the corresponding model card selected.
#### Scenario: Missing or denied model
- **GIVEN** identity is absent or the requested model is unavailable to the viewer
- **WHEN** evidence or a model URL is opened
- **THEN** absent identity remains a label and the registry explicitly reports an unavailable selection without showing another model as the selected one.

