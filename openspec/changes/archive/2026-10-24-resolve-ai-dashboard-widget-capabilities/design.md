# Design: One authoritative compatibility resolution

## Reuse and service boundaries
Reuse `dashboard/domain/WidgetCapabilities`, `VisualizationCompatibility`,
`query/WidgetManifests`, `application/DefinitionValidation` and
`templates/DashboardCompositionPreparation`. ai-agent-service currently obtains
metrics, fields and sources in `DashboardPlanningContext`, then normalizes some
vehicle fields in `DashboardVehicleFields`. Factor this normalization against the
shared resolution result instead of maintaining another list of known field names.

Implement a dashboard application resolver with explicit outbound evidence ports
and existing device/telemetry adapters. Domain compatibility code depends only on
typed metadata. It does not call controllers, databases or HTTP. Controllers map
and validate transport requests. Preserve JWT propagation and current ownership
checks; do not add a generic permissions service or internal authorization bypass.

## Proposed read-only API
Add authenticated POST `/dashboards/composition/capabilities`, with no-store:
`{deviceIds:[...], period:"LAST_1_HOUR"}` plus an optional bounded requested
purpose filter CURRENT/HISTORY/MAP/DOMAIN. ai-agent-service resolves opaque
device refs before calling it; raw device IDs never come from model authority.
Respect existing1-20 selected devices, catalogue/context byte limits and supported
period constraints. Response includes descriptor version, a metadata snapshot
identity, and bounded choices. No dashboard/source mutation or raw readings.

Each typed choice identifies:
- Stable device/source identity and exact field or registered metric identity.
- Data family REGISTERED_NUMERIC_METRIC / RECORDED_VEHICLE_FIELD / DOMAIN_EVIDENCE
  (operator text has no measured-value choice).
- Actual value type, declared unit, label, current/history availability and known
  freshness semantics; a field called nextStop is not assumed to be a human name.
- Compatible widget type/version and view modes from the descriptor and ordinary
  preparation rules, along with any required additional semantic configuration.

ai-agent-service exposes short task-local `dataRef` choices to the model and stores
their stable identity mapping in scoped server state. A ref is an exact data choice,
not an access grant. Bound/truncate large catalogues explicitly: return a narrowing
diagnostic rather than silently dropping the requested source or inventing a match.
Do not embed full catalogue labels/groups repeatedly in model context.

Bad request400, unauthorized401, foreign/missing selected resources404 according to
owning API semantics; semantic incompatibility422. Valid empty data returns an
available capability with sample status EMPTY/UNKNOWN where evidence supports it,
not a missing-metric error. Source/field metadata unavailable due to upstream
failure is typed dependency failure, never an empty successful catalogue.

## Resolution rules
1. Use confirmed device/source selections; do not promote unrelated candidates.
2. Match explicit field/metric semantics and actual data family. A recorded boolean
   cannot be submitted as a registered numeric metric merely to satisfy a chart.
3. Resolve exactly one compatible choice or return meaningful ambiguity. Multiple
   sources or same-label fields are not resolved by arbitrary first-item choice.
4. Determine supported widgets and visualizations through the descriptor and
   preparation constraints. Text history supports list; numeric/boolean history
   supports implemented list/line modes. Preserve unknown intervals and gaps.
5. Keep zero, false, missing and stale distinct. A retained old door observation
   does not become current; unit compatibility does not imply source freshness.
6. Revalidate snapshot identities/current access on continuation and final prepare.
   Metadata changes cause refresh/re-resolution or an honest unsupported result.
   No hardcoded HSL IDs, transport-field aliases or operator-specific fallbacks.

Compatibility must include query/semantic requirements, not only value type:
energy/cost widgets keep counter/tariff/physical-boundary rules; widgets requiring
configuration cannot be advertised as ready solely because a number exists.
Use authoritative preparation for final decisions. Unsupported capabilities
remain unsupported; this change does not generate missing telemetry or widgets.

## Editor and graph acceptance
Existing `WidgetConfiguration`, `VehicleFieldSelection`, shared presentation
settings and manifest choices remain the editing path. Consume common resolution
metadata where data selectors currently duplicate compatibility logic. Do not move
common header settings out of General or offer settings without render support.

Verify existing door graph implementation before adding anything: line/list
selection, boolean labels, false/zero, ordered observation times and broken lines
across missing/long gaps. Recorded intervals indicate observations, not inferred
physical door-opening events. Unknown next-stop IDs display as IDs with an honest
label; human-readable names require a real metadata source.

## Tests, caching and rollout
No cross-user capability cache. Prefer request-scoped resolution; any later cache
must key actor/account/resource/version and remain bounded. Keep old catalogue
GET endpoints and prepared reviews compatible. Add the new read endpoint before
switching model-context refs. Resolver failures preserve the last good review.
Test all data families and capability matrix agreement, two sources, denied access,
field type drift, large catalogues, missing/stale/false/zero, unsupported text line
and no upstream data. Run authenticated Docker/browser HSL smoke separately from
repeatable synthetic fixtures.

## Basis
[OpenAI tool design](https://developers.openai.com/api/docs/guides/function-calling)
recommends moving known argument work into code.
[json-render](https://github.com/vercel-labs/json-render) is an inspectable example
of one typed component catalogue driving model constraints and rendering; reuse
that principle without replacing VoltCanary's existing widget framework.
