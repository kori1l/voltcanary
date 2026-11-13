# Fix real device assistant handoff

## Why
The real HSL bus request is rejected because unrelated counts of telemetry
observations match the multiple-device guard. Missing model configuration also
turns an unsupported request into an empty YAML validation.

## What Changes
- Restrict plural-device rejection to explicit device quantities.
- Preserve one reviewed device creation and describe source connection separately.
- Ask a useful question instead of validating absent simulator YAML.
- Correct the Vehicles assistant page context and verify the complete demo-owner
  device/source/HSL ingestion handoff with a currently active bus.

## Impact
AI planning, assistant context and regression tests. Existing authorized creation,
source setup and HSL connector boundaries remain in force; no new AI MQTT tool.
