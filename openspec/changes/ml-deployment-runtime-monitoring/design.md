## Context

Safe ML deployment is not just a policy table. The runtime must apply the policy
exactly and store enough traceability for operators to know which path produced
each prediction.

Current behavior stores policy metadata, but device-type resolution and canary
selection are incomplete. Demo model seeding can make the registry appear more
production-ready than it is.

## Goals

- Make deployment policy resolution true in live recalculation.
- Implement deterministic canary selection.
- Preserve baseline/rule fallback.
- Separate rule risk and ML risk.
- Provide operational and model-quality monitoring.
- Make demo models non-production-only.

## Non-Goals

- Do not implement new training algorithms in this change.
- Do not remove shadow mode or fallback behavior.
- Do not expose internal artifact URIs to ordinary users.

## Design

### Policy Resolution

Live recalculation should resolve policies using:

1. device override;
2. device type;
3. account;
4. system;
5. fallback.

Analytics-service must know the device type. It can load it from device-service,
cache it in a read model, or receive it in the analytics job context.

### Canary Selection

Canary inclusion should be deterministic:

`hash(accountId, deviceId, modelVersionId) % 100 < canaryPercent`

The prediction trace should record:

- deployment mode;
- canary percent;
- canary included true/false;
- selected model id;
- fallback reason.

### Traceability

Prediction traceability should distinguish:

- rule risk;
- ML anomaly score;
- ML degradation score;
- ML failure risk;
- active prediction;
- shadow prediction;
- canary candidate prediction;
- fallback prediction.

### Monitoring

Metrics should include:

- inference latency by model;
- inference errors by model/status;
- fallback rate by model/device type;
- feature schema mismatch rate;
- missing feature rate;
- shadow vs active score difference;
- canary inclusion count;
- drift by feature distribution;
- false alerts per device/month when labels exist;
- recall/lead time after outcomes arrive.

### Demo Models

Demo model seeding must be disabled outside local/demo profiles. Production
model registry should show only real trained models and explicit system
heuristics.

## Risks

- Device metadata lookup can add latency. Cache or read models may be needed.
- Monitoring quality metrics depend on delayed outcome labels.
- Canary must be deterministic to avoid unstable user-facing behavior.
