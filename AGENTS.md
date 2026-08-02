# Agent Instructions

## OpenSpec Workflow

VoltCanary uses OpenSpec as the source of truth for non-trivial product,
architecture, dependency, API, data, security, deployment, and cross-service
changes.

## Active ML Change Order

When choosing the next active ML OpenSpec change, use the first unfinished
change in this order unless the user explicitly asks for another one:

1. `close-ml-dataset-lifecycle` - close dataset lifecycle first: external
   import to dataset version, internal telemetry to immutable dataset, and
   quality gates.
2. `materialize-ml-feature-platform` - build feature snapshots next: real
   feature matrices, hashes, and online/offline parity.
3. `unify-ml-artifacts-runtime` - then connect training/runtime: real model
   artifacts, MinIO/S3 storage, ML worker, and model loading by
   `modelVersionId`.
4. `failure-outcome-labeling` - can run partly in parallel with steps 2 and 3,
   but supervised modeling depends on its structured maintenance outcomes and
   horizon labels.
5. `supervised-failure-prediction` - start only after steps 1 through 4 provide
   real datasets, features, artifacts, and labels; implement true failure
   prediction, horizons, evaluation, and risk drivers.
6. `ml-deployment-runtime-monitoring` - do last, or partly in parallel after
   step 3 where safe: runtime-true deployment, canary, device-type policy, and
   monitoring.

If the user asks which ML change to start and does not override the order, run
`npm run openspec:list`, identify the first unfinished change from this list,
and apply that change with `npm run openspec:apply -- <change-id>`.

When a user references an OpenSpec change, spec, or task, or when the requested
work is non-trivial, follow this workflow before editing files:

1. Run `npm run openspec:apply -- <change-id>` before implementation. This is
   the repository's required OpenSpec entrypoint for applying an active change:
   it lists active changes, rejects archived or missing changes, prints
   `show`, `status`, and task instructions, and runs strict validation.
2. If the user has not named a change, run `npm run openspec:list` first and
   ask which active change to apply.
3. Do not treat files under `openspec/archive/` as active work unless the user
   explicitly asks to inspect or restore archived context.
4. Follow the checkbox/task tracking format from the OpenSpec task
   instructions exactly.
5. Read the change artifacts needed for the task:
   `proposal.md`, `design.md`, `tasks.md`, and changed specs under
   `openspec/changes/<change-id>/specs/`.
6. Run `npm run openspec:validate` before making broad changes when feasible,
   and always run it before final response.
7. Implement the tasks. Update `tasks.md` checkboxes only after the related work
   and verification are complete.
8. After all tasks in an active change are complete and the required validation
   commands pass, run `npm run openspec -- archive <change-id>` automatically.
   Do not archive incomplete or failing changes.

If a task appears to require OpenSpec but no suitable active change exists, ask
the user whether to create a new OpenSpec change before implementing.

Small local fixes that do not affect APIs, schemas, architecture, data,
security, deployment, or cross-service behavior may bypass OpenSpec.
