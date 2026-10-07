# Console interface workflows

The implemented console follows [the interface audit](../../target/ui-design-requirements.md). All nine associated OpenSpec changes were completed and archived on 2026-10-07. Their acceptance checklists and verification evidence are retained in the archive; 45 requirements were promoted into nine main specifications.

## Shell and evidence

The responsive shell has a mobile navigation toggle, a main-content skip link and one current destination. Native dialogs contain focus, support Escape, restore opening focus and guard changed drafts. Destructive actions name the affected object and impact; cancellation performs no request.

Dashboard resources load independently. A failed resource displays unavailable evidence, or retained evidence with its named retrieval time and a stale warning. Idle successful sections do not repeat refresh timestamp rows; active refresh and stale evidence retain contextual timestamps. Successful empty responses may show zero; failed responses may not. Registered active devices and recent telemetry are separate counts. Inventory and alert cohorts are explicitly bounded by the request limits. ML monitoring reports fallback-enabled policies with a denominator rather than implying a measured runtime fallback frequency; latency remains unavailable where the API provides none.

## Identity and administration

Login, registration, reset and verification share labeled task forms. Password visibility is explicit. Incoming `token` query parameters are captured in page memory and removed using history replacement; manual token entry remains available. Recovery responses describe eligible accounts and configured delivery without exposing whether an account exists. A successful registration followed by failed login can retry login without repeating registration.

Personal profile, password change and all-session sign-out live in Settings. `/account` remains the administration route. USER and SUPPORT do not load administration lists. ADMIN edits role/status as a draft, then applies it through a named confirmation. User session inspection displays metadata and never refresh-token values. Revoking refresh sessions does not promise immediate invalidation of access JWTs.

## Device setup and imports

Inventory opens with recognizable device icon tiles grouped by location; the compact table remains an optional comparison view. Device registration is distinguished from the last measurement timestamp. The four-step wizard validates each advance, explicitly chooses existing versus new location, and reports failures within the dialog.

After the server returns a location/device/source ID, a retry resumes recorded steps instead of recreating them. Simulator credentials remain in memory; a failed start reuses the saved configuration. Closing and reopening a partially completed wizard retains its in-memory continuation and returns to Review. Reloading the page loses that continuation. A transport failure before an ID is returned cannot establish whether the server committed creation: check inventory before starting a new setup. This is not a server idempotency guarantee.

Device and source CSV imports accept files, offer templates and show numbered local previews. The payload remains the existing plain-text CSV contract. The current server uses comma splitting and does not support quoted embedded commas; the preview rejects that syntax. Server responses can indicate partial success. Correct rejected rows before another import rather than resending already imported rows. References and authorization are still validated by the server.

Source credential rotation/revocation and disable actions explain sender impact before mutation. Analytics exposes baseline-window days while preserving other JSON keys. Simulator presets load as drafts; saved configuration and runtime status are shown separately. Starting HTTP simulation explicitly explains its existing credential rotation behavior.

## Telemetry and alert rules

Overview presents registration, measurement freshness and risk evidence with links to task-specific tabs. Telemetry preserves unavailable selections and reconciles displayed versus available counts. Exact Custom intervals use local timezone labels and second precision; metric analysis provides date fields as a keyboard alternative to brush selection. Charts preserve measured zero and separate explicit missing samples and long sampling gaps. Related-signal explanations are inferred evidence, not confirmed diagnoses.

Alert rule editing uses canonical metric/scope/channel IDs with readable labels, condition-specific parameters and a readable if/then summary. Test uses an explicit interval and reports a separate result; it does not create a rule. Editing preserves existing configuration, cooldown and deduplication fields. Resolution requires a reviewed reason. Notification setup distinguishes configured, enabled, adapter availability and actual test-delivery status. Quiet-hour preferences display their timezone, routing selections and critical bypass; saved unsupported channel choices are preserved with an explanation.

## Maintenance

Timeline keeps device labels visible inside its own horizontal scrolling region. Exact start/end timestamps accompany short windows; minimum-width visual markers do not imply a longer duration. Agenda is the default mobile view. Fleet and device scheduling share named policy fields: alert suppression, analytics exclusion and training exclusion remain independent flags. Invalid or reversed intervals fail locally. Cancel and early finish explain their downstream impact before submission.

Outcome recording separates observed result, actual time, notes and failure-specific evidence. Normal inspections clear hidden failure fields. Existing outcomes are shown with server timestamps and canonical operation/window/device links; operator evidence is not presented as an automated diagnosis.

## ML workspace

Settings, datasets, import, labels, training, models, policies and monitoring are separate tabs. Settings must load the selected account/device scope before saving. Responses for older scopes and dataset selections cannot replace the current selection. Pipeline prerequisites link to remediation and distinguish anomaly detection from horizon-dependent failure prediction.

Dataset preparation still requires a matching server preview before commit. Existing stale-preview and request-order guards remain in place. Import provides a bounded CSV/JSONL sample and visual column mapping while sending the original file through the existing contract. Dataset detail presents validation and lineage; snapshots and training require compatible family/horizon inputs. Label corrections require a reason and retain audit history. Generation shows the actual job scope and allows status refresh without another start.

Model decisions and policy changes are reviewed with named scope, serving mode, fallback and inheritance. Delete keeps the server lifecycle-impact gates. Monitoring shows server configuration/readiness evidence; missing runtime measurements remain unavailable. UI-only changes do not modify training, inference, artifact or deployment contracts and therefore do not trigger the ML production Docker smoke baseline.

## Assistant and providers

Requests summarize allowlisted device, metric and interval context. Advanced JSON rejects credential fields. Editing the request/context/provider/model invalidates its previous plan. Plans show steps, affected objects and readable parameters; read-only execution and reviewed mutating execution have one primary command. Recorded execution results prevent duplicate execution and display per-action failures.

Provider forms follow advertised capabilities, including authentication, tool support and JSON mode. Account-default selection remains server resolved. Credential replacement uses a separate in-memory password field and reviewed impact; returned metadata remains masked. Providers with authentication NONE require no credential. Provider/model removal names the affected configuration.

Alert assignee information remains read-only because the current API exposes no assignment mutation. The console explains this limitation rather than offering a non-functional assignment control.

## Verification evidence

Local browser fixtures render actual route components without restarting the stopped backend. They exercise responsive layout and UI state handling, not live authorization or delivery. Tests cover resource failures and stale responses, focus/draft behavior, canonical IDs, authentication drafts, role visibility, resumed creation, chart gaps and incident lifecycle. Generated browser evidence stays under ignored `artifacts/` directories. Required final checks and remaining browser coverage are tracked in each change's `tasks.md`.

### Validation on 2026-10-06

- Frontend: TypeScript passes; 27 test files / 77 tests pass; production build passes. The existing bundle-size advisory remains (approximately 645 kB before gzip).
- OpenSpec: 26 strict validation items pass. Repository structure passes; reported risks are existing backend/Python sizes and constructor dependencies. The updated route pages no longer trigger the oversized frontend-page warning.
- Browser: populated primary routes checked at 360, 768 and 1440 pixels; all eight ML tabs and seven device tabs checked at those widths. Error/loading/empty resources checked on nine operational routes, with retained-stale dashboard evidence after refresh failure.
- Enlarged presentation: 17 routes checked with authored 200% page scaling and separately doubled computed text sizes. These are fixture simulations, not a claim of native browser text-only zoom certification. No document-level horizontal overflow was observed after the fixes. Tables and Timeline keep intentional scrolling inside named regions.
- Dialogs: mobile scheduling draft, Keep/Discard, Escape/focus restoration, onboarding steps, sessions, outcomes and reviewed label/policy actions inspected. Canonical payloads, cancellation without mutation, secret non-disclosure, late-response rejection, duplicate job prevention and exact second-level keyboard analysis have focused tests.
- Screenshots: ignored `artifacts/console-ui-verification/dashboard-desktop.png`, `maintenance-desktop.png` and `onboarding-mobile.png`.
- Infrastructure stayed stopped; only a temporary frontend server was used for local fixtures and stopped after verification. Live authentication, notification delivery, inference/training and capacity claims require their existing integration verification guides. No runtime/API changes were introduced by this console implementation.

| Change | Implemented coverage | Focused evidence |
| --- | --- | --- |
| establish-console-design-system | Shell, controls, responsive regions, dialogs, selectors, timestamps | AppLayout/Dialog tests; five reference screens; width and enlargement checks |
| fix-console-data-state-semantics | Independent resources, unknown/empty/stale evidence and retry | Resource/Dashboard tests; failure and retained-stale browser states |
| improve-account-auth-console | Auth, recovery, personal security, reviewed administration/sessions | Auth/Admin/Sessions tests; populated account and responsive auth forms |
| improve-device-onboarding-configuration | Inventory, wizard recovery, CSV preview, registry/configuration and simulator | Partial-create/CSV tests; four mobile wizard steps and configuration/simulation tabs |
| improve-device-telemetry-analysis | Overview, exact ranges, selected metrics, gaps, analysis and calculation status | Range/chart/job/keyboard tests; populated deep link and metric dialog |
| improve-alert-notification-console | Inbox, resolution, condition-aware rules, channels and quiet hours | Incident/rule/channel/preferences tests; populated delivery and Email/Telegram forms |
| improve-maintenance-console | Timeline/Agenda, scheduling/impact, cancellation and outcomes | Policy/outcome tests; scheduling guard, short-window view and outcome history |
| improve-ml-console-experience | Pipeline, scope, datasets/import, labels/generation, training, models/policies and monitoring | ML workflow/import/training/scope tests; populated dataset lineage, label and policy review |
| improve-ai-assistant-console | Context, plan review/execution, providers/models and credential rotation | Plan/context/late-response/secret tests; populated provider and read-only plan |

No AGENTS.md or skill update is required: repository architecture, validation commands and agent workflow remain unchanged.

### Device console regression verification on 2026-10-07

Follow-up change: `fix-device-console-regressions`. Restored tiles as the initial inventory view and retained the table and filters. Corrected appliance icon matching so WASHING_MACHINE does not match AC through the substring MACHINE; added a kettle silhouette.

Reproduced the false device-context error under React StrictMode before the fix. Device load feedback now uses the current resource state, exposes the actual API error and a Retry action, and clears on recovery. Location lookup failure remains separate and does not label a configured location as absent. Healthy section timestamps are suppressed; named stale evidence remains visible.

Validation: 30 frontend test files / 84 tests pass; typecheck and production build pass (existing bundle-size advisory). Browser fixtures run actual components under StrictMode: devices, Overview and Analytics checked at 360/768/1280 pixels with no document overflow, no false device error and no idle refresh rows. True API failure retains Retry; stale refresh retains the device card and named retrieval evidence. Screenshot: ignored `artifacts/device-console-repair/device-tiles.png`. These fixtures do not establish live backend availability.
