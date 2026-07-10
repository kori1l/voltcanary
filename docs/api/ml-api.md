# ML API

Machine learning inference and model metadata endpoints.

## POST /inference

Runs deterministic predictive scoring for one device telemetry snapshot or
aggregate feature set. Existing response fields remain present; new fields are
additive.

Request:

```json
{
  "deviceId": "a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16",
  "timestamp": "2026-07-10T10:15:00Z",
  "metrics": {
    "activePowerW": 850.0,
    "voltage": 230.0,
    "current": 5.1,
    "energyGrowth": 1.0
  },
  "baseline": {
    "metric": "activePowerW",
    "mean": 800.0,
    "median": 790.0,
    "p90": 910.0,
    "min": 700.0,
    "max": 950.0,
    "sampleCount": 24
  },
  "recentWindow": {
    "sampleCount": 24,
    "excludedSampleCount": 0,
    "windowStart": "2026-07-09T10:15:00Z",
    "windowEnd": "2026-07-10T10:15:00Z"
  },
  "excludedIntervals": [],
  "modelPreference": {
    "allowBaselineHeuristic": true
  }
}
```

Response:

```json
{
  "deviceId": "a0b7f4dc-2f4b-4d7a-9f9e-5d1f88a5bc16",
  "modelName": "voltcanary-baseline-heuristic",
  "modelVersion": "baseline-heuristic-v1",
  "modelType": "baseline_heuristic",
  "modelStatus": "READY_HEURISTIC",
  "predictionStatus": "BASELINE_HEURISTIC",
  "anomalyScore": 0.05,
  "degradationScore": 0.035,
  "riskLevel": "LOW",
  "confidence": 0.82,
  "signals": [
    {
      "metric": "telemetry",
      "score": 0.05,
      "severity": "LOW",
      "explanation": "Metrics activePowerW, current, energyGrowth, voltage are within expected heuristic ranges."
    }
  ],
  "explanations": [
    "Metrics activePowerW, current, energyGrowth, voltage are within expected heuristic ranges."
  ],
  "recommendedActions": ["Continue normal monitoring."],
  "inputHash": "sha256..."
}
```

`predictionStatus` values:

- `MODEL`: trained model output.
- `BASELINE_HEURISTIC`: deterministic heuristic scoring.
- `FALLBACK`: emergency fallback output.
- `INSUFFICIENT_DATA`: request had no useful numeric telemetry.

Validation and failure responses:

- Empty `metrics` returns `200` with `predictionStatus=INSUFFICIENT_DATA`.
- Invalid JSON, missing required fields, or non-finite numeric metric values
  return `400`.
- Runtime failures return standard service errors; analytics-service treats ML
  unavailability as a degraded dependency and continues rule-based analytics.

## GET /models

Returns active model registry metadata:

```json
[
  {
    "name": "voltcanary-baseline-heuristic",
    "version": "baseline-heuristic-v1",
    "status": "READY_HEURISTIC",
    "modelType": "baseline_heuristic"
  }
]
```

`status` distinguishes heuristic readiness from future trained model readiness
so operators and analytics-service can audit which predictive boundary produced
a result.
