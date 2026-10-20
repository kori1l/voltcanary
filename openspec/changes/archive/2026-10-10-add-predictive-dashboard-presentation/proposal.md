# Predictive dashboard presentation

## Why
The prediction widget hides stored failure risk and model lineage, so analysts cannot prioritize inspections from a populated dashboard.

## What Changes
- Add an explicit prediction table with risk, horizon, confidence, state and device drill-down.
- Configure optional model/time columns, risk sorting and ML workspace navigation.
- Populate three synthetic persona dashboards for the existing local demonstration account using genuine trained predictions.

## Impact
Frontend presentation and mapping of stored failureHorizonDays to the existing dashboard horizonDays field. Existing authorized query contracts and ML inference semantics remain unchanged.
