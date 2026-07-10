# Change Proposal: Platform Dependency Upgrade And Verification

## Why

VoltCanary is still pre-production, so dependency upgrades should optimize for a better long-term foundation rather than minimal migration risk. The project currently has several dependency surfaces:

- backend Java/Spring dependencies managed in `backend/pom.xml`;
- frontend React/Vite/TypeScript dependencies in `frontend/package.json`;
- ML-service Python dependencies in `ml-service/requirements.txt` and `ml-service/pyproject.toml`;
- container/runtime images in `docker-compose.yml` and `deploy/k8s/base/*.yaml`;
- CI tool versions in `.github/workflows/ci.yml`;
- OpenSpec tooling in the root `package.json`.

Some dependency sets are pinned, some are floating, and some runtime/toolchain versions are inconsistent. For example, frontend CI uses Node 22 while frontend `engines.node` still allows Node 14, and ML-service requirements are unpinned.

## What Changes

- Establish an explicit dependency upgrade policy for the pre-production phase.
- Inventory outdated dependencies across backend, frontend, ML-service, CI, and container images.
- Upgrade to current stable major/minor versions where compatible, accepting necessary source changes.
- Align toolchain versions across local metadata, CI, Docker Compose, and Kubernetes manifests.
- Pin Python dependencies and produce reproducible install behavior.
- Replace compatibility-by-accident with explicit verification commands and documented rollback points.
- Add or update scripts where useful so dependency verification is repeatable from the repository.

## Non-Goals

- Do not preserve backward compatibility with older local Node versions such as Node 14.
- Do not keep old dependency versions only to avoid source-level migration work.
- Do not introduce production rollout, blue/green deployment, or live data migration procedures in this change.
- Do not change product behavior unless required by upgraded libraries.
- Do not upgrade Java beyond the current Java 21 baseline unless every backend and CI target is updated together.

## Upgrade Bias

Because the project is not in production:

- Prefer current stable versions over conservative patch-only upgrades.
- Accept breaking framework upgrades when they remove technical debt or align with current ecosystem defaults.
- Keep data-destructive actions out of automated verification, but do not over-optimize for backwards-compatible runtime behavior.
- Update code, tests, docs, and configs in the same change instead of layering temporary compatibility shims.

## Impacted Areas

- `package.json`
- `package-lock.json`
- `.node-version`
- `.nvmrc`
- `.github/workflows/ci.yml`
- `frontend/package.json`
- `frontend/package-lock.json`
- `frontend/tsconfig*.json`
- `frontend/vite.config.ts`
- `backend/pom.xml`
- backend service source/tests if Spring or library APIs changed
- `ml-service/requirements.txt`
- `ml-service/pyproject.toml`
- optional Python lock/constraints file if adopted
- `docker-compose.yml`
- `deploy/k8s/base/*.yaml`
- `deploy/helm/voltcanary/values*.yaml`
- `README.md` and developer setup docs

## Open Questions

- Should the project standardize on Node 22 LTS or a newer stable Node line for all JavaScript tooling?
- Should ML-service adopt a lock tool such as `uv` or keep pinned `requirements.txt` only?
- Should container image upgrades be done in the same implementation change or staged after application dependency upgrades pass?
- Should root `npm test` remain OpenSpec-only, or become an orchestration command for OpenSpec, frontend, backend, and ML checks?
