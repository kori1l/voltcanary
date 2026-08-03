---
name: voltcanary-openspec
description: VoltCanary OpenSpec workflow for non-trivial product, architecture, dependency, API, data, security, deployment, cross-service, ML, analytics, alerting, recommendation, and frontend workflow changes. Use when Codex needs to select, apply, validate, update tasks for, or archive an OpenSpec change.
---

# VoltCanary OpenSpec

## Decision

Use OpenSpec before implementation when the work affects product behavior,
service APIs, DTOs, event schemas, database shape, retention, security,
deployment, cross-service flows, ML semantics, analytics, alerts,
recommendations, or frontend workflows backed by backend behavior.

Bypass OpenSpec only for small local fixes that do not change those surfaces.

## Workflow

1. Run `npm run openspec:list` unless the user already named a change.
2. If the user named a change, run `npm run openspec:apply -- <change-id>`
   before editing implementation files.
3. If no change was named, ask which active change to apply. If the user asks
   for the next active ML work, choose from the currently active output of
   `openspec:list`; do not use archived changes or stale hard-coded change
   sequences.
4. Read the applied change's `proposal.md`, `design.md`, `tasks.md`, and changed
   specs under `openspec/changes/<change-id>/specs/`.
5. Follow the task checkbox format printed by `openspec:apply`. Check a box only
   after implementation and verification for that task are complete.
6. Run `npm run openspec:validate` before broad changes when feasible and
   always before the final response for OpenSpec-governed work.
7. Use `$voltcanary-docs-guidelines` when the change affects docs, contracts,
   architecture notes, operating guides, or agent workflow.
8. Before finalizing, check whether `AGENTS.md` or project skills need updates
   because the change altered repository structure, agent workflow, validation
   commands, or architecture rules. Update them only when they would otherwise
   become stale.
9. Archive with `npm run openspec -- archive <change-id>` only after every
   task is complete and required validation passes.

## Context

Treat `openspec/project.md` as the project-level policy. Use `docs/api/*.md`,
`docs/events/*.md`, `docs/architecture/*.md`, `docs/adr/*.md`, and
`docs/flows/*.md` as source context that must stay aligned with implemented
OpenSpec work.

Ignore archived change directories unless the user explicitly asks to inspect
or restore archived context.
