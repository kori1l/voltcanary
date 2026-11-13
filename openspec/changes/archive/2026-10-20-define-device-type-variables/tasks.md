## 1. Implementation and verification

- [x] 1.1 Introduce focused definition/revision/binding/bundle domain and ports with immutable IDs, lifecycle and quotas.
- [x] 1.2 Add portable schema, uniqueness, audit and expected-version transactions; verify H2 and PostgreSQL migrations.
- [x] 1.3 Implement account/type-scoped CRUD, publish and archive endpoints with public/internal route authorization tests.
- [x] 1.4 Validate bounded AST aliases, numeric/unit contracts, reserved keys and immutable output identity.
- [x] 1.5 Implement atomic binding/input-retention validation and explicit 409 dependency conflicts.
- [x] 1.6 Implement future-device/source default inheritance and deterministic UNBOUND handling for ambiguous sources.
- [x] 1.7 Implement resumable type-wide rollout, pinned existing bindings and per-source progress/failure reads.
- [x] 1.8 Persist immutable bundles and transactional publication intent for the next change, including disabled generations.
- [x] 1.9 Verify foreign-account/workspace denial, concurrent edits, archive/history retention and legacy template compatibility.
- [x] 1.10 Update current device API/architecture docs and ADR when implemented; run focused backend, structure and strict OpenSpec checks.

## Implementation evidence

See [current verification](../../../../docs/current/flows/device-variable-verification.md),
[current architecture](../../../../docs/current/architecture/device-variables.md) and
[operation commands](../../../../docs/current/operations/device-variables.md).
All ten tasks are implemented and verified for the declared first-version scope. This change remains active for review.

