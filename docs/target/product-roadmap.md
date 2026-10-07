# Product Roadmap Context

VoltCanary is a predictive IoT analytics platform for connected appliances and
small physical assets. The current product slice focuses on account-owned
devices, telemetry ingestion, analytics, alerts, simulator workflows, and ML
lifecycle tooling.

## Current Product Slice

The implemented system can collect telemetry from source-backed devices, store
time-series readings, calculate baselines and risk, create alerts, suppress or
resolve alerts during maintenance windows, run simulations, and manage ML
datasets, labels, feature snapshots, training runs, model registry state, and
deployment policies.

The first-stage telemetry model is centered on smart-plug style metrics:

- `activePowerW`
- `voltageV`
- `currentA`
- `energyWh`
- relay state
- RSSI or signal quality
- online/offline state where available

## Target Product Direction

The platform should continue to describe predictions as signs, deviations,
probabilities, confidence levels, and recommended checks. It must not claim
exact failure diagnosis without enough data and a validated model contract.

Later product expansion may add:

- temperature, humidity, pressure, vibration, open/closed, leak, CO2, and air
  quality sensors;
- external weather and device API context;
- dedicated recommendation, notification, reporting, weather-adapter,
  vendor-adapter, and model-registry services if the current service ownership
  becomes too large;
- B2C smart home, small business, DIY/Home Assistant, small industrial asset,
  and B2B predictive maintenance packaging.
- recommendation and model-inference Kafka events if downstream projections
  need asynchronous copies of those analytics records.

These are target directions, not current runtime contracts.

## User-Created Dashboards

Users should be able to create private dashboards from task templates or a
blank canvas, choose real device/metric bindings, configure time and scope,
arrange widgets, and save a personal default. Team publication depends on
workspace membership and resource grants; dashboard editing must not grant
access to devices, incident mutations, ML configuration, or tariffs.

The authoritative future requirements are
[Custom dashboards](custom-dashboards.md): business segments and personas,
widget contracts, editor/draft/revision workflows, filter and time semantics,
energy/quality calculations, permissions, proposed APIs, performance budgets,
and scenario-level acceptance. Private dashboards come first; team dashboards
and business cost/cycle calculations require their explicit domain dependencies.
This capability is not implemented by the current fixed Dashboard page.

The [active OpenSpec program](../../openspec/changes/README.md) separates current
UI/UX repairs from private dashboards, typed widget queries, the editor and
templates, verified energy aggregates, workspace membership/team publication,
and later tariff/operational metrics. All implementation tasks remain open.
