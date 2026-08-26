---
name: voltcanary-docs-guidelines
description: VoltCanary documentation and guideline workflow for docs/, ADRs, API contracts, event contracts, architecture docs, operations guides, verification flows, OpenSpec proposal/design/tasks alignment, and deciding whether AGENTS.md or project skills need updates. Use when Codex creates, updates, reviews, or audits project documentation or development guidelines.
---

# VoltCanary Docs Guidelines

## Source Map

Use the existing documentation tree instead of creating parallel summaries:

- Documentation entrypoint: `docs/README.md`
- Architecture: `docs/architecture/*.md`
- API contracts: `docs/api/*.md`
- Event contracts: `docs/events/*.md`
- ADRs: `docs/adr/*.md`
- Operational guides: `docs/operations/`, `deploy/`
- Verification flows: `docs/flows/*.md`
- Project policy and specs: `openspec/project.md`, `openspec/specs/`
- Active changes: `openspec/changes/<change-id>/`

Do not treat archived OpenSpec directories as active source unless the user asks
to inspect or restore archived context.

## Documentation Rules

Prefer one authoritative document per topic. Update the existing source when it
exists; create a new file only when the topic has no clear home.

Every documentation folder with multiple markdown files should have a
`README.md` index. When creating, deleting, or moving a document, update the
nearest folder index and `docs/README.md` when the document changes the
top-level navigation.

Write docs as contracts and operating guidance, not marketing copy. Include:

- Concrete endpoints, topics, schemas, states, commands, ports, or ownership.
- Compatibility and failure-mode expectations.
- Verification steps when a human or agent can test the behavior.
- Links or paths to the owning code and OpenSpec change when relevant.

Keep examples small and realistic. Avoid duplicating long code, generated
output, dependency lock data, or content already maintained in another file.

## OpenSpec Alignment

For OpenSpec-governed changes, keep the active change, specs, implementation,
and docs aligned in the same work:

1. Read `proposal.md`, `design.md`, `tasks.md`, and changed specs.
2. Update `docs/api`, `docs/events`, `docs/architecture`, `docs/operations`, or
   `docs/flows` when the implemented behavior changes public or operational
   understanding.
3. Update task checkboxes only after docs and verification are complete.
4. Before finalizing, check whether `AGENTS.md` or project skills need updates
   because the change altered repository structure, agent workflow, validation
   commands, or architecture rules. Update them only when they would otherwise
   become stale.

## ADR Style

Use ADRs for durable architecture decisions, not implementation notes. Keep the
shape consistent with existing `docs/adr/ADR-*.md` files:

- State the decision and context.
- Record consequences and tradeoffs.
- Point to affected services, docs, specs, or verification flows.
- Do not rewrite historical ADRs unless correcting an error; add a new ADR for
  a new decision or supersession.

## API And Event Docs

For API docs, include route, method, auth/internal boundary, request/response
shape, status/error behavior, and backward-compatibility notes.

For event docs, include topic, version, producer, consumers, payload fields,
ordering/idempotency expectations, and compatibility rules.

Keep API and event docs consistent with frontend API clients, backend DTOs, and
OpenSpec specs. If the behavior is intentionally not implemented yet, say so
explicitly instead of implying readiness.

## Agent Guidelines

Keep `AGENTS.md` short: project snapshot, skill routing, OpenSpec trigger rule,
and validation baselines only.

Put detailed agent workflow in the most specific project skill. Add a new skill
only when a task family repeats and needs project-specific context that would be
too noisy for `AGENTS.md`.
