# UI/UX And Dashboard Execution Sequence

Routing updated on2026-10-23. The table below preserves the historical16-change
UI/dashboard program from2026-10-08; that program is completed according to the
[program index](../../../../openspec/changes/README.md). It is not the active
queue for the current AI dashboard conversation work.

## Current Routing

- For the redesigned assistant (intents, plans, server skills and economical model
  routing), follow the [orchestration program](../../../../openspec/changes/README.md#ai-assistant-orchestration-program)
  before the historical AI/dashboard sequences below. It reuses their unfinished
  acceptance without restarting paid campaigns or requiring a dependency cycle.

- For AI dashboard conversation reliability, use the six-stage order in the
  [reliability program](../../../../openspec/changes/README.md#ai-dashboard-reliability-program)
  and [skill selection rules](../SKILL.md#ai-dashboard-reliability-sequence).
  Continue an eligible in-progress stage, otherwise select the earliest unfinished
  eligible stage from that index. The evaluation baseline is completed/archived;
  do not restart it or mistake its recorded failures for unfinished harness work.
- Old preview tasks11/12 are delegated to that program. Their parent change being
  active/in progress does not take priority or require duplicate implementation.
- Ordinary UI/dashboard requests use current active proposals and their own
  prerequisites. Do not revive the completed table below. Explicit deletion work
  remains separate; AI device creation/security uses its dedicated skill section.

The reliability index owns its order and dependency coverage; this reference
does not maintain a second copy. Updating routing alone marks no tasks complete
and does not authorize implementing the queue.

## Historical Queue (Completed)

| Position | Change | Delivery purpose |
| --- | --- | --- |
| 1 | `fix-console-evidence-state-transitions` | P0 resource truth, scoped stale evidence, local retry |
| 2 | `fix-ml-pipeline-lineage-readiness` | P0 selected ML lineage rather than unrelated ready assets |
| 3 | `improve-console-context-and-session-recovery` | URL context, session return, scoped drafts and assistant context |
| 4 | `improve-console-command-feedback` | Mutation outcomes, duplicate-command guards and error feedback |
| 5 | `improve-fleet-browsing-and-device-activation` | Pagination, bounded search and first-measurement workflow |
| 6 | `improve-telemetry-history-and-readable-evidence` | Historical metrics, extrema/gaps and readable values |
| 7 | `improve-console-accessibility-and-page-delivery` | Shared accessibility/mobile controls and route delivery |
| 8 | `add-private-dashboard-domain` | Private definitions, drafts, revisions and preferences |
| 9 | `add-dashboard-widget-query-engine` | Authorized typed queries, full-scope counts and provenance |
| 10 | `add-dashboard-visual-editor` | Personal dashboard creation, layout, autosave and publication |
| 11 | `add-dashboard-task-templates` | Task/business templates and real resource binding |
| 12 | `add-dashboard-energy-aggregates` | Verified energy methods and readiness of Energy template |
| 13 | `add-workspace-resource-membership` | Membership and resource rights before team sharing |
| 14 | `add-team-dashboard-publication` | Shared revisions, audience and independent dashboard grants |
| 15 | `add-dashboard-tariff-cost-estimates` | Evidence-qualified cost after energy and tariff contracts |
| 16 | `add-dashboard-operational-period-metrics` | Schedules, confirmed cycles and downtime facts |

Positions 1–7 repair the console, 8–12 deliver private dashboards with energy,
13–14 enable team use, and 15–16 add business calculations. The Energy template
is visibly capability-gated at position 11 and becomes available after 12;
do not fabricate energy to make the earlier template appear complete.

## Selection And Continuation

AI dashboard reliability follows the dedicated routing above. Other AI-assisted
device/dashboard creation follows the AI program routing in
[SKILL.md](../SKILL.md#ai-creation-and-identity-hardening-sequence), not this
historical UI/dashboard queue. Explicit security-only requests remain scoped
to the identity-hardening change; they do not authorize the AI feature program.

1. Run `npm.cmd run openspec:list`; only currently active queue members are
   candidates. Do not use recency sorting in its output as execution priority.
2. If the user names a change, retain that target and check its proposal's hard
   dependencies. A user-directed order overrides the default queue, not the
   need for prerequisite behavior. Perform prerequisite work only within the
   authorized scope; explain unresolved prerequisites when selection cannot
   proceed. Do not silently substitute an unrelated feature.
3. For an unqualified "next change" in this program, continue the active
   in-progress queue member before opening a new one. In-progress means actual
   implementation/checklist evidence, not merely complete proposal artifacts.
   If multiple queue members are in progress without a named target, choose
   the earliest position with satisfied prerequisites and report the choice.
4. Otherwise choose the earliest eligible unfinished active queue member.
   The historical table is completed; its first row is not a default target for
   new work. If no matching member is active, inspect current applicable proposals.
5. For "next UI/UX", consider positions 1–7. For "next dashboards", consider
   8–16 together with their transitive hard prerequisites, including required
   UI changes. Do not demand all preceding optional UI items merely because
   they are earlier in the full queue. Pick an unfinished required prerequisite
   first using the same queue order and explain why it unlocks the requested
   dashboard target.
6. For a narrower named feature such as energy, team dashboards or tariffs,
   consider its target plus transitive prerequisites rather than unrelated
   earlier changes. Notification requests use the notification sequence in
   SKILL.md; other ML requests are not automatically dashboard work.

## Completion And Dependency Gates

- Read proposal/design/tasks/specs and apply the selected change before code.
  A checked task counts only with its implementation and required verification.
  Complete proposal artifacts or a 0-task list are not implementation success.
- An active dependency with unfinished tasks or failed required checks is not
  satisfied. A dependency absent from `openspec:list` is not automatically
  complete: verify promoted main specs, implemented behavior and current
  verification evidence. Do not revive archived changes, inspect unrelated
  archives or assume an intentionally incomplete archive is a release gate.
- A verified completed active change should be finalized/archived according
  to the main skill before treating its removal from the queue as complete.
  Do not mark tasks while only updating this sequence or its documentation.
- At integration, enforce the proposal's hard prerequisites even when its
  backend groundwork can be prepared independently. Private dashboards do not
  depend on workspace/team membership; team publication does. Workspace tariff
  use additionally requires membership, while private tariffs do not.
- If a required dependency is missing or lacks reliable completion evidence,
  report that concrete gap instead of skipping it. Continue independent work
  only where the user's scope permits it. If no applicable active queue member
  remains, say so and inspect current active proposals instead of resurrecting
  this fixed queue or implementing unrelated work.

When a proposal dependency or program scope changes, update routing and the
owning program index together. Retain this table only for historical UI order;
the reliability program index owns the new six-stage queue. Keep feature-specific
acceptance and requirement mapping in their owning changes.
