# Analytics API

Analytics and derived insight endpoints.

## Prediction Metadata

`GET /analytics/devices/{deviceId}/predictions/latest` returns the latest
persisted ML prediction trace. Existing score fields remain available and the
response now includes traceability metadata:

```json
{
  "deviceId": "a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16",
  "modelName": "voltcanary-baseline-heuristic",
  "modelVersion": "baseline-heuristic-v1",
  "predictionStatus": "BASELINE_HEURISTIC",
  "anomalyScore": 0.12,
  "degradationScore": 0.08,
  "confidence": 0.74,
  "inputHash": "sha256...",
  "signals": [
    {
      "metric": "activePowerW",
      "score": 0.12,
      "severity": "LOW",
      "explanation": "activePowerW is within absolute heuristic thresholds."
    }
  ],
  "explanations": ["activePowerW is within absolute heuristic thresholds."],
  "recommendedActions": ["Continue normal monitoring."],
  "createdAt": "2026-07-10T10:15:00Z"
}
```

`predictionStatus` mirrors ML-service status values where available:

- `MODEL`: trained ML model output.
- `BASELINE_HEURISTIC`: deterministic ML-service heuristic output.
- `FALLBACK`: fallback output.
- `INSUFFICIENT_DATA`: ML-service could not score useful telemetry.
- `UNAVAILABLE`: analytics-service could not obtain ML output and persisted a
  degraded trace instead of failing the recalculation job.
- `SUPPRESSED_MAINTENANCE`: ML inference was skipped because maintenance
  exclusion removed the useful ML input.

Risk and recommendation generation remains rule-based for this iteration, but
persisted ML `signals`, `explanations`, `recommendedActions`, and `inputHash`
are exposed so frontend and support workflows can explain which model boundary
contributed to the prediction trace.

## Maintenance Exclusion Semantics

Analytics-service consumes `maintenance-window.changed.v1` into a local read
model and reconciles missed changes from device-service. It does not call
device-service synchronously in telemetry or recalculation hot paths.

When a maintenance window overlaps analytics input:

- `excludeFromAnalytics=true` removes samples from baselines, feature sets,
  anomaly detection, risk score input, and recommendation input.
- If every useful sample in a recalculation window is excluded, the analytics
  job is marked `SKIPPED` with `skipReason=MAINTENANCE_FULL_COVERAGE`.
- `excludeFromMl=true` is sent to ml-service as explicit `excludedIntervals` in
  the inference request contract.
- Raw telemetry remains queryable; only derived analytics interpretation is
  affected.

Operational tables:

- `maintenance_windows_read_model`: local projection keyed by maintenance
  window id.
- `maintenance_projection_checkpoints`: reconciliation cursor.
- `analytics_jobs.skip_reason`: records maintenance full-coverage skips.

Reconciliation fetches:

```http
GET /internal/maintenance-windows/changes?updatedAfter={instant}&limit={n}
```

The fetch uses `X-Internal-Service-Token` and is guarded by a scheduler lock so
analytics instances can scale independently.
