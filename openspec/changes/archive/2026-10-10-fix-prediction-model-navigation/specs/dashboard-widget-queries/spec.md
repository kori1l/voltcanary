## ADDED Requirements
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
