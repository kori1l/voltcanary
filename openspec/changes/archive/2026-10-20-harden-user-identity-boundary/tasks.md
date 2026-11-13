## 1. Implementation and verification

- [x] 1.1 Inventory all identity-header consumers, user/internal/collector routes, callers, tests and scripts; record compatibility migration matrix.
- [x] 1.2 Implement shared JWT verification adapters and migrate all user-facing identity resolution without header-based fallback or new privilege bypass.
- [x] 1.3 Migrate inter-service user calls and verify narrow internal/collector authentication remains distinct and fail-closed.
- [x] 1.4 Add common-test signed JWT fixtures and migrate positive HTTP tests; retain negative spoofed subject/role/account/workspace tests.
- [x] 1.5 Migrate live smoke/provisioning scripts to isolated test-account login/refresh and scoped cleanup without credential output.
- [x] 1.6 Review Compose/local Kubernetes diagnostic exposure, use loopback where appropriate and verify direct authenticated debugging remains usable.
- [x] 1.7 Run two-account gateway/direct-port adversarial integration tests and real create-device/create-dashboard/read/cleanup automation; run backend suites and applicable existing ML/ingestion/variable/vehicle regression gates.
- [x] 1.8 Update current security/API/operations/verification docs and affected guidance only where necessary; run OpenSpec and structure checks and record actual evidence before completion.

