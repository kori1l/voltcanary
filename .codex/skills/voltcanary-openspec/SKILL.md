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

## Target Ingestion Architecture Sequence

When the user asks for the next active ingestion architecture work and no change
is explicitly named, prefer this order while these changes are active:

1. `define-ingestion-adapter-contracts`
2. `route-http-ingestion-through-adapters`
3. `route-mqtt-ingestion-through-adapters`
4. `replicate-ingestion-source-runtime-config`
5. `harden-ingestion-shared-scaling-state`
6. `build-ingestion-connector-runtime`
7. `add-http-polling-ingestion-adapter`
8. `add-modbus-tcp-ingestion-adapter`
9. `add-opc-ua-ingestion-adapter`
10. `verify-ingestion-horizontal-scale`

Do not preserve weak internal ingestion structures merely for backward
compatibility while these changes are being implemented; VoltCanary is
pre-production. Preserve a public contract only when the active change
explicitly says to do so or when keeping it reduces scope without compromising
the target architecture.

Implement `define-ingestion-adapter-contracts` first because every later slice
depends on the canonical adapter boundary. HTTP and MQTT may be implemented
sequentially or in parallel after the contracts are in place. Complete source
runtime config and shared scaling state before active connectors. Build the
connector runtime before adding HTTP polling, Modbus TCP, or OPC UA. Keep
`verify-ingestion-horizontal-scale` last so it validates the full target
runtime across replicas.

## Context

Treat `openspec/project.md` as the project-level policy. Use `docs/api/*.md`,
`docs/events/*.md`, `docs/architecture/*.md`, `docs/adr/*.md`, and
`docs/flows/*.md` as source context that must stay aligned with implemented
OpenSpec work.

Ignore archived change directories unless the user explicitly asks to inspect
or restore archived context.
