## Why

Dashboard energy batches avoid HTTP N+1 but still fetch unrelated device sources
and repeatedly scan the union of samples for each interval and meter epoch.

## What Changes

- Select only authorized canonical source/metric/physical windows in one SQL read.
- Merge overlapping read ranges to avoid duplicate sample delivery.
- Build one source/metric index and use binary range slices for calculations.
- Preserve existing bracketing, quality counters, resets, replacement, comparison,
  raw maintenance consumption and bounded read semantics.

## Capabilities

### New Capabilities
- `energy-reading-selection`: Narrow bounded owner reads and indexed interval selection.

### Modified Capabilities
None. The public energy response and calculator contract remain compatible.

## Impact

Telemetry repository/service, a vendor-specific covering index, energy regression
tests and existing dashboard verification/API documentation. No frontend changes.
