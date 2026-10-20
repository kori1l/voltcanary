## 1. Implementation and verification

- [x] 1.1 Extend alert rule schema/DTO/validation with variableId/source/operator/unit and optimistic rule version.
- [x] 1.2 Add authorized source-bound variable capability validation without hard-coded metric fallback.
- [x] 1.3 Implement analytics accepted-measurement consumer with durable idempotency and separate LIVE variable-rule path.
- [x] 1.4 Extend internal rule snapshot contract/cache with unavailable retry behavior and revocation policy.
- [x] 1.5 Implement shared threshold predicate for exact live samples and bounded full-resolution dry run.
- [x] 1.6 Persist structured source/variable/revision/rule evidence and compatible anomaly outbox publication atomically.
- [x] 1.7 Extend alert evidence/dedup handling while preserving maintenance, cooldown and notification port lifecycle.
- [x] 1.8 Test spike hidden by chart buckets, replay, distinct sources/rules, edited formula, dependency failure and no duplicate legacy job.
- [x] 1.9 Test BACKFILL/stale/missing/invalid/unbound/disabled/denied and maintenance exclusions never create live matches; enforce explicit maxLiveAgeSeconds.
- [x] 1.10 Document current rule/event/alert contracts when delivered; run common/analytics/alert checks, structure and strict OpenSpec validation.

## Implementation evidence

See [current verification](../../../docs/current/flows/device-variable-verification.md),
[current architecture](../../../docs/current/architecture/device-variables.md) and
[operation commands](../../../docs/current/operations/device-variables.md).
All ten tasks are implemented and verified for the declared first-version scope. This change remains active for review.
