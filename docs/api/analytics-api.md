# Analytics API

Analytics and derived insight endpoints.

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
