# AI dashboard creation: design

## Reuse and ownership

Reuse GET /dashboard-templates and /dashboard-widget-types, existing
DashboardTaskTemplates, definition validation, binding validation and
POST /dashboards with Idempotency-Key. Current creation produces a private
dashboard with publishedRevision=0 and a saved draft; preserve that semantic,
not a newly invented dashboard status or automatic publication. Result opens
the existing editor. Do not duplicate widget math, authorization or the template
catalogue in ActionPlanner/system prompt. AI does not query persistence directly.

## Intent and assembly

CREATE_DASHBOARD fields: name, templateRef/version, deviceRefs, optional
sourceRefs/metricRefs, supported period preset and bounded presentation choices.
No ownership/workspace/grant/publication fields, raw DashboardDefinition, URL,
SQL, JavaScript or arbitrary layout expressions. Personal resources only.
Use the current template IDs discovered from the API, not invented names.
First-version limits: one dashboard, up to 20 devices and 12 widgets per task;
catalogue pagination/selection and clear limit errors instead of silent truncation.

Authoritative template assembly belongs to dashboard-service alongside existing
templates and manifests. If existing APIs cannot instantiate a template server-side,
add POST /dashboard-templates/{id}/prepare with ordinary user JWT. It accepts
typed bindings, version and supported options; resolves current metadata through
existing authorized adapters, validates and returns a bounded definition plus
binding diagnostics and digest without persisting a dashboard or account.
Prepare and create share the same template assembler/validators, avoiding a
second template implementation in AI. Domain creation remains the final guard.

Before provider submission expose only authorized candidate summaries. During
assembly validate every device/source/variable ref, type, metric unit and query
capability. Foreign bindings fail the whole request without disclosing names.
Missing metrics produce NEEDS_INPUT or explicit user-selected removal; never
invent a metric or silently replace the device/source. A supported metric with
no samples may yield a widget honestly showing no data, distinct from invalid
binding and from numeric zero. Unknown formula/source ambiguity is clarified.

## Review and creation

Preview shows template/version, named devices, panels, period and private draft
scope. Preview may read through existing authorized bounded query APIs; no
resource creation, published revision or default preference changes. Freeze
prepared payload/version/digest in the task and invalidate review when selections
change. At execute revalidate current bindings, template support and user account;
template semantic change requires re-prepare rather than replacing reviewed panels.
The actual POST /dashboards receives a server-compiled payload and the stable
action key, with current JWT. Reuse domain transaction/receipt handling and
test timeout-after-commit; receipt lookup cannot leak a revoked resource.

Creation does not grant ongoing telemetry access: normal widget queries renew
authorization, including cached snapshots. A private draft is not published,
shared, made default or populated with demo evidence. Optional source setup or
new-device handoff is a separate task; failure to create a dashboard never
deletes a previously created device. Legacy assistant tools remain explicit.

## Acceptance

Deterministic fixture: at least 40 Russian requests split 20 supported, 10
missing/ambiguous/incompatible bindings and 10 foreign refs/unsupported/injected
cases. At least 18/20 supported requests prepare the expected template/bindings
within one clarification on the configured small model; all unsafe or unresolved
cases remain non-executable. Record provider/model, prompt/schema/template
versions and outcomes; no stronger-model substitution or fabricated PASS.
CI fixtures stub provider, real model run is a separate recorded acceptance gate.

Test two-account prepared payloads and cached/ref reuse, spoofed context,
revocation between preview/create/query, unavailable sources, zero/no data,
parallel execute, restart, template drift and repeated idempotent POST. Run AI
and dashboard backend suites with PostgreSQL, frontend typecheck/test/build,
and real gateway/browser preview/cancel/create/open/cleanup. Preserve ordinary
manual editor, sharing and existing widget tests. No fleet capacity claim.
