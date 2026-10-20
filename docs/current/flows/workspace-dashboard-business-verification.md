# Workspace and business dashboard verification

This flow covers the six dashboard/workspace changes accepted on 2026-10-10.
The procedure below remains reproducible; actual results and limits are recorded
in the acceptance checkpoint.

1. Create two active users, a workspace and a team containing a strict member
   subset. Associate an owner's active resource account with reviewed consent.
   Give users different device scopes and different action capabilities.
2. Read each domain through the gateway. Verify other-workspace IDs, spoofed
   authority headers, inactive membership/account and stale JWT after removal
   cannot return resources or hidden counts. Verify personal owner regressions.
3. Associate a dashboard, preview recipients and grant VIEW/EDIT separately from
   PUBLISH and resource access. Use two live sessions, save author-specific
   drafts, publish concurrently and recover from a stale base conflict.
4. Revoke resource and dashboard grants while viewing. Reload/query and verify
   old evidence is cleared. Test keyboard/mobile sharing and read-only kiosk.
   Copy/export/import must require fresh authorized bindings and carry no ACL.
5. Configure physical energy meters and non-overlapping flat tariff intervals.
   Exercise known energy×rate, revision boundaries, missing rate, partial
   energy, mixed currency, rounding and archive with historical evidence.
6. Import duplicate confirmed facts, conflicting identities, incomplete and
   aborted cycles, corrections, DST/holiday schedules and overlapping downtime.
   Confirm no cycles from peaks and no downtime from missing telemetry.
7. Configure/save/reload each compatible visualization. Check all-zero, missing,
   partial, negative and incompatible unit/series evidence; keyboard inspection
   must expose values. Check existing minimal defaults, named screens, flexible
   grids, right drawer, hidden navigation and sidebar links.
8. After all implementation, run affected Maven tests, frontend typecheck/test/
   build, OpenSpec validation and structure checks. Run the existing energy
   query30-day profile and tenant integration smoke before archiving. Record
   actual commands, results and any outstanding gates in each active change.
# Demonstration dashboard

For an explicitly selected local `@example.invalid` account, set the process-only
`DEMO_DASHBOARD_PASSWORD` and run `python scripts/seed-demo-dashboard.py --email <email>`.
The account must already exist. The script uses its signed login, seeds synthetic
measurements in local TimescaleDB, retains existing meter identities and sources,
and publishes an Overview/Trends/Energy dashboard as the personal default.
Existing source history is preserved. Repeating the command creates a new dashboard;
it does not delete old dashboards or change user roles. Passwords and bearer tokens
are never written to summaries. The sample EUR tariff is explicitly a demonstration
estimate; existing tariffs take precedence. Review the actual panel coverage after seeding.

For the denser synthetic stress scenario, run `python scripts/enrich-demo-dashboard.py
--email <email> --dashboard <owned-dashboard-id>` with the same process-only password.
It revises the selected demo dashboard, creates reviewed meter-source epochs and
confirmed operational facts, and records cyclic loads, short power peaks, temperature
excursions and voltage dips. The overview has 18 widgets. This is a local scenario;
these events do not describe real appliance faults. Charts compare same-unit device
series with distinct colors. Covered-energy distributions require complete coverage
and exact device totals; cadence is inferred independently for each source.


## Acceptance checkpoint: 2026-10-10

| Gate | Result | Local evidence |
| --- | --- | --- |
| All backend modules, including PostgreSQL/Kafka integration | 454 tests, zero failures/errors/skips, exit 0 | `artifacts/final-backend-tests.log`, Surefire XML |
| Frontend typecheck / full tests / build | 85 files, 265 tests; all passed | `artifacts/final-frontend-*.log` |
| ML unittest / production Docker smoke | 62 tests; trained artifact and inference passed | `artifacts/final-ml-tests.log`, `final-ml-smoke-summary.json` |
| Membership and shared publication | Scope, spoof denial, per-author conflicts, revoke and cache clearing passed | `artifacts/final-workspace-smoke.log` |
| Business/lifecycle acceptance | Tariff history, atomic import rollback, foreign audience, audited transfer, departed-owner consent withdrawal passed | `artifacts/final-business-acceptance.json` |
| Energy 30-day synthetic profile | 12 devices, 69,144 samples, 8,640 kWh COMPLETE; both methods and zero baseline passed | `artifacts/final-energy-profile.json` |
| Browser | Two signed actors: 2 versus 1 equipment; keyboard dialog closure, mobile 390 px without overflow, restricted editor actions and read-only kiosk | Local browser walkthrough; current unit/workflow tests |
| OpenSpec / structure | Strict 47/47 before archive; structure passed | `artifacts/final-openspec.log`, `final-structure.log` |

Reproduce gateway gates with `python scripts/workspace-dashboard-smoke.py` and
`python scripts/workspace-business-acceptance.py` against the local running stack.
They create reserved disposable fixture identities; the business acceptance
summary contains no credentials or bearer tokens. The first smoke's local
browser fixture file is ignored and must never be committed or shared.

The latest energy profile ran concurrently with backend/frontend tests: warm P95
307.342 ms, cold queries 1.640–2.290 s. These measurements validate bounded local
queries and are not production capacity evidence. Telemetry retention is unchanged.
One passing Kafka test JVM emitted a Surefire 30-second shutdown termination
warning after System.exit(0). Existing structure and dependency deprecation
warnings remain. Optional ML lifecycle deletion cleanup was not part of this
smoke. Previously deferred native speech, OS zoom and physical pointer checks
remain deferred, with no new claims of passing them.

Windows Docker bind mounts can preserve old Vite modules in a browser cache:
restart frontend after edits and verify a fresh browser origin/session when an
existing tab still renders older code. Preserve the user's desktop viewport.
AGENTS.md and skill routing remain applicable without changes.

### Predictive persona demonstration

Run `python scripts/seed-role-dashboards.py --email <reserved-demo-email>` with
DEMO_DASHBOARD_PASSWORD set only in the invoking process. It requires the six
existing synthetic refrigeration devices and the running local Compose stack.
It refreshes their telemetry, creates a separate synthetic teaching fixture,
trains a FAILURE_PREDICTION artifact through the dataset/label/feature pipeline,
approves only after its configured gates, deploys it and recalculates predictions.
It then publishes predictive analyst, service and energy dashboards, favorites
them and preserves the personal default. `--resume` reuses the saved account-bound
telemetry interval after an interrupted run. Fixture evidence and summaries stay
under ignored artifacts; no credentials or tokens are written there.

The teaching fixture telemetry is replaced on retries to prevent duplicate rows
and oversized training requests; previously materialized dataset versions remain
immutable. Only reserved local synthetic fixtures are supported. These forecasts
illustrate the workflow and are not validation of real appliance failure accuracy.

Prediction panels display the stored trained failure score as a percentage,
horizon, confidence and state. Confidence is not measured accuracy. Appearance
settings optionally show model/version and calculation time and sort the returned
page by failure risk. Unknown or heuristic failure probabilities stay unavailable.
Each authorized device name links to its device view; panel Navigation can target
ML workspace. Calculation settings expose the 1-50 row limit; Data settings permit
multiple selected equipment bindings without discarding remaining selections.

Maintenance agenda rows also resolve deviceIds to authorized equipment names,
format stored dates for reading, and support showMaintenanceFlags (default true)
to hide suppression/exclusion columns without altering maintenance semantics.
Local verification: analytics Maven reactor 137 tests passed; frontend full
suite 270 passed, followed by 15 focused tests after presentation refinements;
typecheck/build, strict OpenSpec and structure checks passed. Browser verification
uses a 1600px desktop viewport, stored 7-day trained predictions, authorized
equipment drill-down and persona-specific evidence. Energy coverage is COMPLETE
for the fixed demo interval. Freshness is live and can become stale after seeding;
a static teaching history does not imply continuously connected equipment.

Prediction model navigation check: refresh the predictive demo dashboard, activate
Trained model, verify tab=models and modelId match the stored response identity,
verify only that authorized model card is shown, then use Show all models to
restore the registry. Missing or invalid identities and heuristic states remain
labels. Focused frontend tests cover link identity, invalid/heuristic cases and
selected/unavailable cards; PostgreSQL integration verifies actual response model
identity rather than requested policy identity, authorization and read-only access.

### Compact inventory and absent risk

Device inventory keeps search, location, registration status and view in a compact
top row. Location opens a paged searchable popover; Options contains read refresh.
Current-page counts remain explicit and page controls appear only for multiple
pages. Reference failures are disclosed once in expandable setup feedback; device
reads remain independent. Refresh devices retries both inventory and references.
Healthy cards avoid duplicate retrieval timestamps; real errors retain retries.
Risk HTTP 404 for an authorized inventory device is treated as an absent stored
assessment (Risk not calculated), never as a healthy zero. HTTP 403, network and
server failures retain denial/error/stale behavior. No backend contract changes.

Alert and maintenance filters use compact top toolbars and shared paged entity
popovers. Alert counters form a small strip; sorting/read-refresh are in Options,
and existing incident actions/filter scopes remain unchanged. Maintenance interval
semantics are retained and its calendar uses the full working width.
Simulator presets are loaded only for simulator onboarding or Demo Tools. A local
icon indicates checked service availability, distinct from runtime running state;
unavailable references have a local retry. Ordinary inventory does not fetch
simulator references. Saved setup recovery blocks Add until the account checkpoint
has been checked/restored, preventing a transient enabled-action race.

Alert summary scope is cached only after a successful read and reset on effect
cleanup. The StrictMode initial-read regression test confirms summary and inbox
finish loading after replay. Browser verification on the demo account showed
19 open alerts, one critical, a working critical filter (one incident), and an
enabled Refresh button; the alert toolbar is 68px tall at desktop width. Inventory
shows all seven devices, supports location filtering and table switching, and
has no unrelated simulator warning. Demo Tools reports Simulator available.
The maintenance timeline renders seven devices and twelve operations with top
filters. Validation: full frontend suite 280 passing tests before the final alert
fix, then six focused alert tests including its new regression; typecheck,
production build and strict OpenSpec validation passed after the fix.
