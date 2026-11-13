# Change: Harden stage-four operation contracts and replay recorded scenarios

## Why
The user requested completion and scenario checks of stage4, explicitly excluding
stages5/6. A fresh replay on the designated demo account recorded a model output
limit followed by lost dashboard intent: a short name answer became CREATE_DEVICE.
The earlier stage4 corpus remains historical evidence, not a passing release gate.

## What Changes
- Preserve the same dashboard task after failed preparation and short replies.
- Ground strict operation response schemas in current data choices and omit
  operations requiring a nonexistent base review from initial composition.
- Include short sanitized field hints in renewed data references so speed and
  longitude choices remain visibly distinguishable while references stay scoped.
- Ask the genuine unresolved source question before rejecting a generated view
  mismatch, so a source-list request does not waste composition repair calls.
- Record bounded provider response status/usage counters for failure analysis,
  without private reasoning or raw provider bodies.
- Replay stage4 scenarios through durable conversation APIs, export per-turn
  history/traces, check exact reviewed/created equivalence and inspect browser UI.

## Scope and dependencies
Reuses archived semantic compiler, current conversation history/trace and the
in-progress grounding work. No stage5 postcondition verifier, stage6 repair
coordinator/budget, new retry loop or default rollout. Keep historical results
unchanged and report remaining model or UI failures explicitly.
