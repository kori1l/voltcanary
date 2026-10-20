## ADDED Requirements

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
