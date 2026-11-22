import { VehicleFieldSelection } from "../../../vehicles/VehicleFieldSelection";
import { variableDescriptors, type VariableDescriptor } from "../../device-variables/variablesApi";
import { VehicleFleetSources } from "../../../vehicles/VehicleFleetSources";
import { useEffect, useState, useMemo, type ReactNode } from "react";
import { VehicleSourceSelector } from "../../../vehicles/VehicleSourceSelector";
import { Field } from "../../../components/Field";
import { EntitySelector } from "../../../components/EntitySelector";
import { getTelemetryMetrics, type TelemetryMetricMetadata } from "../../../telemetry/telemetryApi";
import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
import type { WidgetManifest } from "../../../dashboards/dashboardQueriesApi";
import { workspaceTelemetryMetrics } from "../model/dashboardBusinessApi";
import {
  dashboardDeviceOptions,
  workspaceDeviceOptions,
  vehicleDeviceOptions,
} from "../model/dashboardEntityOptions";
import { EnergyMeterSetup } from "./EnergyMeterSetup";
import { EnergyTariffSetup } from "./EnergyTariffSetup";
import { OperationalFactsSetup } from "./OperationalFactsSetup";
import { WidgetPresentationSettings } from "./WidgetPresentationSettings";
import { WidgetAppearanceSettings } from "./WidgetAppearanceSettings";
import { ConfiguredRecordsSettings } from "./ConfiguredRecordsSettings";
import { configuredRecordsSelection } from "../model/configuredRecordsQuery";
import { RuleMeasurePicker } from "./RuleMeasurePicker";
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
export function WidgetConfiguration({
  widget,
  manifest,
  onSave,
  onCancel,
  layoutControls,
}: {
  layoutControls?: ReactNode;
  widget: DashboardWidget;
  manifest: WidgetManifest;
  onSave: (widget: DashboardWidget) => void;
  onCancel: () => void;
}) {
  const initialSeries = Array.isArray(widget.query?.series)
    ? (widget.query.series as { deviceId: string; metric: string }[])
    : [];
  const [draft, setDraft] = useState<DashboardWidget>({
    ...widget,
    binding: widget.binding ?? { mode: "ALL" },
    query: { ...widget.query, metric: widget.query?.metric ?? initialSeries[0]?.metric },
    presentation: widget.presentation ?? {},
    navigation: widget.navigation ?? {},
    payload: widget.payload ?? {},
  });
  const [metrics, setMetrics] = useState<TelemetryMetricMetadata[]>([]);
  const [metricError, setMetricError] = useState<string | null>(null);
  const [section, setSection] = useState(
    !manifest.supported && layoutControls ? "Layout" : "General",
  );
  const positionWidget =
    manifest.capabilities?.binding.sourceRequired ??
    ["vehicle-map", "vehicle-value", "vehicle-timeline"].includes(widget.type);
  const deviceId = draft.binding.deviceIds?.[0] ?? "";
  const loadDevices = useMemo(
    () =>
      draft.binding.workspaceId && draft.binding.accountId
        ? workspaceDeviceOptions(draft.binding.workspaceId, draft.binding.accountId)
        : positionWidget
          ? vehicleDeviceOptions
          : dashboardDeviceOptions,
    [draft.binding.workspaceId, draft.binding.accountId, positionWidget],
  );
  const needsMetric =
    manifest.capabilities?.binding.metricRequired ??
    ["latest-value", "time-series", "metric-summary"].includes(widget.type);
  const configuredRecords = widget.type === "configured-records";
  const ruleMeasure = widget.type === "rule-window-result";
  const configuredEvidence = configuredRecords || ruleMeasure;
  const needsBinding = !configuredEvidence && manifest.capabilities?.dataKind !== "OPERATOR_TEXT";
  const [variableOptions, setVariableOptions] = useState<VariableDescriptor[]>([]);
  const [variableError, setVariableError] = useState("");
  const [fieldCompatible, setFieldCompatible] = useState(true);
  const fieldTypes =
    manifest.capabilities?.visualizationValueTypes?.[
      String(draft.presentation.visualization ?? "list")
    ] ?? manifest.capabilities?.valueTypes;
  useEffect(() => {
    const controller = new AbortController();
    setVariableOptions([]);
    setVariableError("");
    if (deviceId && widget.type === "time-series" && !draft.binding.workspaceId)
      void variableDescriptors(deviceId, controller.signal)
        .then((items) => {
          if (!controller.signal.aborted) setVariableOptions(items);
        })
        .catch(() => {
          if (!controller.signal.aborted)
            setVariableError("Stored variable choices are unavailable.");
        });
    return () => controller.abort();
  }, [deviceId, widget.type, draft.binding.workspaceId]);
  useEffect(() => {
    setMetrics([]);
    setMetricError(null);
    if (!deviceId || !needsMetric) return;
    const controller = new AbortController();
    const asOf = Date.now();
    void (
      draft.binding.workspaceId && draft.binding.accountId
        ? workspaceTelemetryMetrics(
            draft.binding.workspaceId,
            draft.binding.accountId,
            deviceId,
            new Date(asOf - 30 * 86400000).toISOString(),
            new Date(asOf).toISOString(),
            controller.signal,
          )
        : getTelemetryMetrics(
            deviceId,
            new Date(asOf - 30 * 86400000).toISOString(),
            new Date(asOf).toISOString(),
            controller.signal,
          )
    )
      .then((items) => {
        if (!controller.signal.aborted) setMetrics(items);
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          setMetricError(error instanceof Error ? error.message : "Metrics unavailable");
      });
    return () => controller.abort();
  }, [deviceId, needsMetric, draft.binding.workspaceId, draft.binding.accountId]);
  const variableSelection = (Array.isArray(draft.query.series) ? draft.query.series[0] : null) as {
    variableId?: string;
    dataSourceId?: string;
  } | null;
  const selectedVariable = variableOptions.find(
    (item) =>
      item.variableId === variableSelection?.variableId &&
      item.dataSourceId === variableSelection?.dataSourceId,
  );
  const selected = selectedVariable
    ? {
        name: selectedVariable.metricToken,
        unit: selectedVariable.unit,
        displayName: selectedVariable.label,
        type: "number",
        min: -1e12,
        max: 1e12,
      }
    : metrics.find(
        (metric) => metric.name === draft.query.metric && !metric.name.startsWith("var_"),
      );
  const fleetSources = draft.query.vehicleSources as Record<string, string> | undefined;
  const fleetMap = positionWidget && !!fleetSources;
  const invalidRecords =
    configuredRecords &&
    (Boolean(draft.binding.workspaceId) || !configuredRecordsSelection(draft.query).columns.length);
  const invalidMeasure =
    ruleMeasure &&
    (Boolean(draft.binding.workspaceId) ||
      !uuid.test(String(draft.query.epoch ?? "")) ||
      !uuid.test(String(draft.query.measure ?? "")));
  const invalid =
    invalidRecords ||
    invalidMeasure ||
    !fieldCompatible ||
    (["vehicle-value", "vehicle-timeline"].includes(widget.type) &&
      (!draft.presentation.vehicleField || (draft.binding.deviceIds?.length ?? 0) !== 1)) ||
    (positionWidget &&
      (!deviceId ||
        (fleetMap
          ? (draft.binding.deviceIds ?? []).some((id) => !fleetSources?.[id])
          : !draft.query.sourceId) ||
        draft.binding.mode !== "FIXED")) ||
    (needsMetric && (!selected || !deviceId)) ||
    (draft.binding.mode === "FIXED" &&
      ((draft.binding.deviceIds ?? []).some((id) => !id) ||
        new Set(draft.binding.deviceIds).size !== (draft.binding.deviceIds?.length ?? 0)));
  function query(patch: Record<string, unknown>) {
    setDraft((prior) => ({ ...prior, query: { ...prior.query, ...patch } }));
  }
  const comparisonSeries = Array.isArray(draft.query.series)
    ? (draft.query.series as { deviceId: string; metric: string }[]).slice(1)
    : [];
  return (
    <form
      className="dashboard-config"
      onSubmit={(event) => {
        event.preventDefault();
        if (invalid) return;
        const series = selectedVariable
          ? [
              {
                deviceId,
                metric: selectedVariable.metricToken,
                variableId: selectedVariable.variableId,
                dataSourceId: selectedVariable.dataSourceId,
              },
            ]
          : [
              { deviceId, metric: selected?.name ?? "" },
              ...comparisonSeries
                .filter((s) => s.deviceId)
                .map((s) => ({ ...s, metric: selected!.name })),
            ];
        onSave({
          ...draft,
          binding:
            widget.type === "time-series"
              ? { ...draft.binding, deviceIds: [...new Set(series.map((s) => s.deviceId))] }
              : draft.binding,
          query: {
            ...draft.query,
            ...(selected ? { metric: selected.name, unit: selected.unit } : {}),
            ...(widget.type === "time-series" ? { series } : {}),
          },
        });
      }}
    >
      <nav className="dashboard-config-sections" aria-label="Panel settings sections">
        {[
          "General",
          ...(manifest.supported
            ? [
                "Data",
                ...(manifest.timeBasis !== "NONE" && !configuredEvidence ? ["Calculation"] : []),
                "Appearance",
                "Navigation",
              ]
            : []),
          ...(layoutControls ? ["Layout"] : []),
        ].map((name) => (
          <button
            type="button"
            key={name}
            className="ghost-button"
            aria-pressed={section === name}
            onClick={() => setSection(name)}
          >
            {name}
          </button>
        ))}
      </nav>
      {!fieldCompatible ? (
        <p role="alert">
          The selected field is incompatible with this visualization. Choose another visualization
          or a compatible field in Data.
        </p>
      ) : null}
      <fieldset hidden={section !== "General"}>
        <legend>General</legend>
        <Field label="Widget title" required>
          {(props) => (
            <input
              {...props}
              value={draft.title}
              maxLength={120}
              onChange={(event) => setDraft({ ...draft, title: event.target.value })}
            />
          )}
        </Field>
        <WidgetPresentationSettings
          manifest={manifest}
          section="GENERAL"
          presentation={draft.presentation}
          onChange={(presentation) => setDraft({ ...draft, presentation })}
        />
        <small>
          Hiding the title affects viewing and preview. The editor keeps the title and drag controls
          available.
        </small>
      </fieldset>
      <fieldset hidden={section !== "Data"}>
        <legend>Data</legend>
        {configuredRecords ? (
          <ConfiguredRecordsSettings
            widget={draft}
            onChange={(change) => setDraft((prior) => ({ ...prior, ...change }))}
          />
        ) : null}
        {ruleMeasure ? (
          <>
            {(
              [
                { key: "epoch", label: "Deployment ID" },
                { key: "measure", label: "Stored measure ID" },
              ] as const
            ).map(({ key, label }) => (
              <label key={key}>
                {label}
                <input
                  value={String(draft.query[key] ?? "")}
                  onChange={(event) => {
                    const value = event.target.value.trim().toLowerCase();
                    setDraft((prior) => ({
                      ...prior,
                      query:
                        key === "epoch"
                          ? { epoch: value, measure: "" }
                          : { epoch: prior.query.epoch ?? "", measure: value },
                    }));
                  }}
                />
              </label>
            ))}
            {!draft.binding.workspaceId && uuid.test(String(draft.query.epoch ?? "")) ? (
              <RuleMeasurePicker
                key={String(draft.query.epoch)}
                epoch={String(draft.query.epoch)}
                onSelect={(measure) =>
                  setDraft((prior) => ({ ...prior, query: { epoch: prior.query.epoch, measure } }))
                }
              />
            ) : null}
          </>
        ) : null}
        {widget.type === "time-series" && deviceId && !draft.binding.workspaceId ? (
          <label>
            Stored variable and source
            <select
              value={
                selectedVariable
                  ? selectedVariable.variableId + ":" + selectedVariable.dataSourceId
                  : ""
              }
              onChange={(event) => {
                const variable = variableOptions.find(
                  (item) => item.variableId + ":" + item.dataSourceId === event.target.value,
                );
                query(
                  variable
                    ? {
                        metric: variable.metricToken,
                        unit: variable.unit,
                        series: [
                          {
                            deviceId,
                            metric: variable.metricToken,
                            variableId: variable.variableId,
                            dataSourceId: variable.dataSourceId,
                          },
                        ],
                      }
                    : { metric: undefined, series: [] },
                );
              }}
            >
              <option value="">Use a registered metric</option>
              {variableOptions.map((item) => (
                <option
                  key={item.variableId + item.dataSourceId}
                  value={item.variableId + ":" + item.dataSourceId}
                >
                  {item.label} · {item.sourceId} · {item.unit}
                </option>
              ))}
            </select>
            {variableError ? <small>{variableError}</small> : null}
          </label>
        ) : null}
        {widget.type === "context-note" ? (
          <label>
            Context note{" "}
            <textarea
              maxLength={1000}
              value={String(draft.payload.text ?? "")}
              onChange={(event) =>
                setDraft({ ...draft, payload: { ...draft.payload, text: event.target.value } })
              }
            />
          </label>
        ) : null}
        <label hidden={positionWidget || !needsBinding}>
          Scope{" "}
          <select
            value={draft.binding.mode ?? "ALL"}
            onChange={(event) =>
              setDraft({
                ...draft,
                binding: {
                  ...draft.binding,
                  mode: event.target.value as DashboardWidget["binding"]["mode"],
                },
              })
            }
          >
            <option value="ALL">All permitted resources</option>
            <option value="DYNAMIC">Follow runtime filters</option>
            <option value="FIXED">Selected equipment</option>
            <option value="UNBOUND">Unbound</option>
          </select>
        </label>
        {needsBinding ? (
          fleetMap ? (
            <VehicleFleetSources draft={draft} onChange={setDraft} loadOptions={loadDevices} />
          ) : (
            <EntitySelector
              label="Binding device"
              options={[]}
              loadOptions={loadDevices}
              value={deviceId}
              onChange={(id) =>
                setDraft({
                  ...draft,
                  binding: {
                    ...draft.binding,
                    mode: "FIXED",
                    deviceIds: id
                      ? [
                          id,
                          ...(!needsMetric && !positionWidget
                            ? (draft.binding.deviceIds ?? [])
                                .slice(1)
                                .filter((other) => other !== id)
                            : []),
                        ]
                      : [],
                  },
                  query: {
                    ...draft.query,
                    metric: undefined,
                    unit: undefined,
                    series: [],
                    sourceId: undefined,
                  },
                })
              }
            />
          )
        ) : null}
        {positionWidget && !fleetMap ? (
          <VehicleSourceSelector
            key={deviceId}
            deviceId={deviceId}
            value={String(draft.query.sourceId ?? "")}
            onChange={(sourceId) => query({ sourceId })}
          />
        ) : null}
        {["vehicle-value", "vehicle-timeline"].includes(widget.type) ? (
          <VehicleFieldSelection
            deviceId={deviceId}
            sourceId={String(draft.query.sourceId ?? "")}
            value={String(draft.presentation.vehicleField ?? "")}
            allowedTypes={fieldTypes}
            widgetType={widget.type}
            visualization={
              widget.type === "vehicle-timeline"
                ? String(draft.presentation.visualization ?? "list")
                : undefined
            }
            onValidityChange={setFieldCompatible}
            onChange={(vehicleField) =>
              setDraft({ ...draft, presentation: { ...draft.presentation, vehicleField } })
            }
          />
        ) : null}
        {needsMetric && !selectedVariable ? (
          <Field
            label="Registered metric"
            required
            error={metricError ?? undefined}
            hint={
              !metrics.length
                ? "Select a device with recorded metrics in the last 30 days."
                : undefined
            }
          >
            {(props) => (
              <select
                {...props}
                value={String(draft.query.metric ?? "")}
                onChange={(event) =>
                  query({
                    metric: event.target.value,
                    series: selectedVariable ? [] : draft.query.series,
                  })
                }
              >
                <option value="">Select metric</option>
                {draft.query.metric && !selected ? (
                  <option value={String(draft.query.metric)} disabled>
                    Unavailable metric
                  </option>
                ) : null}
                {metrics
                  .filter((metric) => !metric.name.startsWith("var_"))
                  .map((metric) => (
                    <option key={metric.name} value={metric.name}>
                      {metric.displayName} · {metric.unit}
                    </option>
                  ))}
              </select>
            )}
          </Field>
        ) : null}
        {needsBinding ? (
          <small>
            Units: {selected?.unit ?? "As recorded by the evidence owner"}. Unavailable metrics
            never become zero.
          </small>
        ) : null}
        {!needsMetric && !positionWidget && draft.binding.mode === "FIXED" ? (
          <div className="dashboard-comparison-series">
            {(draft.binding.deviceIds ?? []).slice(1).map((id, index) => (
              <div key={index}>
                <EntitySelector
                  label={`Selected equipment ${index + 2}`}
                  options={[]}
                  loadOptions={loadDevices}
                  value={id}
                  onChange={(value) =>
                    setDraft({
                      ...draft,
                      binding: {
                        ...draft.binding,
                        deviceIds: (draft.binding.deviceIds ?? []).map((prior, i) =>
                          i === index + 1 ? value : prior,
                        ),
                      },
                    })
                  }
                />
                <button
                  type="button"
                  className="ghost-button"
                  onClick={() =>
                    setDraft({
                      ...draft,
                      binding: {
                        ...draft.binding,
                        deviceIds: (draft.binding.deviceIds ?? []).filter(
                          (_, i) => i !== index + 1,
                        ),
                      },
                    })
                  }
                >
                  Remove equipment {index + 2}
                </button>
              </div>
            ))}
            <button
              type="button"
              className="ghost-button"
              disabled={
                !deviceId ||
                (draft.binding.deviceIds?.length ?? 0) >= 50 ||
                draft.binding.deviceIds?.includes("")
              }
              onClick={() =>
                setDraft({
                  ...draft,
                  binding: {
                    ...draft.binding,
                    deviceIds: [...(draft.binding.deviceIds ?? []), ""],
                  },
                })
              }
            >
              Add selected equipment
            </button>
          </div>
        ) : null}
        {widget.type === "time-series" && !selectedVariable ? (
          <div className="dashboard-comparison-series">
            <small>Compare the same recorded metric across equipment on one colored chart.</small>
            {comparisonSeries.map((series, index) => (
              <div key={index}>
                <EntitySelector
                  label={`Comparison equipment ${index + 1}`}
                  options={[]}
                  loadOptions={loadDevices}
                  value={series.deviceId}
                  onChange={(id) =>
                    query({
                      series: [
                        { deviceId, metric: String(draft.query.metric ?? "") },
                        ...comparisonSeries.map((s, i) =>
                          i === index ? { ...s, deviceId: id } : s,
                        ),
                      ],
                    })
                  }
                />
                <button
                  type="button"
                  className="ghost-button"
                  onClick={() =>
                    query({
                      series: [
                        { deviceId, metric: String(draft.query.metric ?? "") },
                        ...comparisonSeries.filter((_, i) => i !== index),
                      ],
                    })
                  }
                >
                  Remove comparison {index + 1}
                </button>
              </div>
            ))}
            <button
              type="button"
              className="ghost-button"
              disabled={comparisonSeries.length >= 7 || !selected}
              onClick={() =>
                query({
                  series: [
                    { deviceId, metric: String(draft.query.metric ?? "") },
                    ...comparisonSeries,
                    { deviceId: "", metric: String(draft.query.metric ?? "") },
                  ],
                })
              }
            >
              Add comparison equipment
            </button>
          </div>
        ) : null}
        {widget.type === "estimated-cost" ? (
          <EnergyTariffSetup
            accountId={draft.binding.accountId}
            workspaceId={draft.binding.workspaceId}
          />
        ) : null}
        {widget.type === "uptime-or-cycle" && deviceId ? (
          <OperationalFactsSetup
            key={deviceId}
            deviceId={deviceId}
            accountId={draft.binding.accountId}
            workspaceId={draft.binding.workspaceId}
          />
        ) : null}
        {["estimated-cost", "uptime-or-cycle"].includes(widget.type) ? (
          <label>
            <input
              type="checkbox"
              checked={draft.query.independentMetersConfirmed === true}
              onChange={(event) => query({ independentMetersConfirmed: event.target.checked })}
            />
            I reviewed the independent meter boundary for these energy calculations
          </label>
        ) : null}
        {["energy-period", "peak-and-ranking"].includes(widget.type) ? (
          <>
            <label>
              <input
                type="checkbox"
                checked={draft.query.independentMetersConfirmed === true}
                onChange={(event) => query({ independentMetersConfirmed: event.target.checked })}
              />
              I reviewed this scope and selected independent physical meters
            </label>
            <p>
              Use one upstream meter or its independent children. Duplicate identities and known
              parent/submeter overlap are rejected. Missing energy evidence stays partial or
              unavailable.
            </p>
            {deviceId ? (
              <EnergyMeterSetup key={deviceId} deviceId={deviceId} />
            ) : (
              <p>
                Select equipment to set up its physical meter. Review every included meter when
                using all resources or runtime filters.
              </p>
            )}
          </>
        ) : null}
      </fieldset>
      <fieldset hidden={section !== "Calculation"}>
        <legend>Calculation and time</legend>
        {manifest.reducers.length ? (
          <label>
            Calculation{" "}
            <select
              value={String(draft.query.reducer ?? manifest.reducers[0])}
              onChange={(event) => query({ reducer: event.target.value })}
            >
              {manifest.reducers.map((reducer) => (
                <option key={reducer}>
                  {{
                    RAW: "Recorded samples",
                    AVG: "Average",
                    MEAN: "Average",
                    MIN: "Minimum",
                    MAX: "Maximum",
                    P95: "95th percentile",
                  }[reducer] ?? reducer}
                </option>
              ))}
            </select>
          </label>
        ) : null}
        <label>
          Time basis{" "}
          <select
            value={String((draft.query.time as { mode?: string } | undefined)?.mode ?? "INHERITED")}
            onChange={(event) =>
              query({
                time: {
                  mode: event.target.value,
                  preset: event.target.value === "CALENDAR" ? "TODAY" : "LAST_24_HOURS",
                  timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
                },
              })
            }
          >
            <option value="INHERITED">Dashboard time</option>
            <option value="RELATIVE">Relative interval</option>
            <option value="CALENDAR">Calendar day</option>
          </select>
        </label>
        {widget.type === "incident-list" ? (
          <label>
            Incident basis{" "}
            <select
              value={String(draft.query.incidentMode ?? "ACTIVE")}
              onChange={(event) => query({ incidentMode: event.target.value })}
            >
              <option value="ACTIVE">Current active incidents</option>
              <option value="CREATED">Created within interval</option>
            </select>
          </label>
        ) : null}
        <small>
          Recorded evidence · {manifest.timeBasis}. This configuration does not change alert rules.
        </small>
        {[
          "risk-list",
          "prediction-evidence",
          "incident-list",
          "maintenance-agenda",
          "peak-and-ranking",
        ].includes(widget.type) ? (
          <label>
            Maximum displayed rows
            <input
              type="number"
              min={1}
              max={50}
              step={1}
              value={Number(draft.query.rows ?? 10)}
              onChange={(event) =>
                query({
                  rows: Math.min(50, Math.max(1, Math.trunc(Number(event.target.value) || 1))),
                })
              }
            />
          </label>
        ) : null}
        {(draft.query.time as { mode?: string } | undefined)?.mode === "RELATIVE" ? (
          <label>
            Widget relative period
            <select
              value={String((draft.query.time as { preset?: string }).preset ?? "LAST_24_HOURS")}
              onChange={(event) =>
                query({ time: { ...(draft.query.time as object), preset: event.target.value } })
              }
            >
              {[
                "LAST_1_HOUR",
                "LAST_6_HOURS",
                "LAST_24_HOURS",
                "LAST_7_DAYS",
                "LAST_30_DAYS",
                ...(widget.type === "maintenance-agenda" ? ["NEXT_7_DAYS", "NEXT_30_DAYS"] : []),
              ].map((preset) => (
                <option key={preset}>{preset}</option>
              ))}
            </select>
          </label>
        ) : null}
      </fieldset>
      <fieldset hidden={section !== "Appearance"}>
        <legend>Appearance</legend>
        <WidgetAppearanceSettings widget={draft} manifest={manifest} onChange={setDraft} />
      </fieldset>
      <fieldset hidden={section !== "Navigation"}>
        <legend>Drill-down</legend>
        <label>
          Destination{" "}
          <select
            value={draft.navigation.href ?? ""}
            onChange={(event) =>
              setDraft({
                ...draft,
                navigation: event.target.value ? { href: event.target.value } : {},
              })
            }
          >
            <option value="">None</option>
            <option value="/devices">Devices</option>
            <option value="/alerts">Alerts</option>
            <option value="/maintenance">Maintenance</option>
            <option value="/settings/ml">ML workspace</option>
            {deviceId ? <option value={`/devices/${deviceId}`}>Bound device</option> : null}
          </select>
        </label>
      </fieldset>
      {layoutControls ? <div hidden={section !== "Layout"}>{layoutControls}</div> : null}
      <div className="action-row" hidden={section === "Layout"}>
        <button type="submit" className="primary-button" disabled={invalid}>
          Apply changes
        </button>
        <button type="button" className="ghost-button" onClick={onCancel}>
          Cancel
        </button>
      </div>
    </form>
  );
}
