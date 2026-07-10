# Agent Instructions

## OpenSpec Workflow

VoltCanary uses OpenSpec as the source of truth for non-trivial product,
architecture, dependency, API, data, security, deployment, and cross-service
changes.

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
8. Do not run `openspec archive <change-id>` unless the user explicitly approves
   archiving the completed change.

If a task appears to require OpenSpec but no suitable active change exists, ask
the user whether to create a new OpenSpec change before implementing.

Small local fixes that do not affect APIs, schemas, architecture, data,
security, deployment, or cross-service behavior may bypass OpenSpec.
