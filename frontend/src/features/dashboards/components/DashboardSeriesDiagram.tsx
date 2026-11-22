import { useChartViewport } from "../hooks/useChartViewport";
import { useState } from "react";
import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
import type { WidgetQueryPanel } from "../../../dashboards/dashboardQueriesApi";
import { diagramEvidence } from "../model/dashboardVisualization";

const colors = [
  "#2563eb",
  "#f59e0b",
  "#10b981",
  "#f43f5e",
  "#8b5cf6",
  "#06b6d4",
  "#f97316",
  "#64748b",
];
/** Same-unit recorded series share an axis; each source keeps its own gaps. */
export function DashboardSeriesDiagram({
  widget,
  panel,
}: {
  widget: DashboardWidget;
  panel: WidgetQueryPanel;
}) {
  const { ref, width, height } = useChartViewport();
  const left = 52,
    right = width - 16,
    top = 20,
    bottom = height - 30;
  const [focused, setFocused] = useState("");
  const rows = Array.isArray(panel.data?.series)
    ? (panel.data.series as Record<string, unknown>[])
    : [];
  const devices = Array.isArray(panel.data?.devices)
    ? (panel.data.devices as Record<string, unknown>[])
    : [];
  const series = rows.map((row, index) => ({
    color: colors[index % colors.length],
    label:
      String(devices.find((d) => d.id === row.deviceId)?.name ?? "Equipment") +
      " · " +
      String(row.metric),
    evidence: diagramEvidence(widget, { ...panel, data: { ...panel.data, series: [row] } }),
  }));
  const points = series.flatMap((s) => s.evidence.values);
  if (
    series.some((s) => s.evidence.reason) ||
    !panel.time ||
    new Set(points.map((p) => p.unit)).size !== 1
  )
    return (
      <span className="dashboard-state-badge">
        Shared chart requires complete compatible recorded units
      </span>
    );
  const lower = Math.min(...points.map((p) => p.value)),
    upper = Math.max(...points.map((p) => p.value)),
    padding = Math.max(0.1, (upper - lower) * 0.1);
  const rangeAxis = ["C", "V"].includes(points[0]?.unit);
  const min = rangeAxis ? lower - padding : Math.min(0, lower),
    max = rangeAxis ? upper + padding : Math.max(1, upper);
  const x = (at: string) =>
    left +
    ((Date.parse(at) - Date.parse(panel.time!.from)) /
      Math.max(1, Date.parse(panel.time!.to) - Date.parse(panel.time!.from))) *
      (right - left);
  const y = (value: number) => bottom - ((value - min) / (max - min)) * (bottom - top);
  const format = (at: string) =>
    new Date(at).toLocaleTimeString(undefined, { hour: "2-digit", minute: "2-digit" });
  return (
    <div className="dashboard-diagram dashboard-series-diagram">
      <svg
        ref={ref}
        viewBox={`0 0 ${width} ${height}`}
        preserveAspectRatio="none"
        role="group"
        aria-label="Compared recorded series"
      >
        {widget.presentation.showAxes !== false ? (
          <g className="dashboard-series-axis" aria-hidden="true">
            {[0, 0.25, 0.5, 0.75, 1].map((f) => {
              const value = min + (max - min) * f;
              return (
                <g key={f}>
                  <line x1={left} x2={right} y1={y(value)} y2={y(value)} />
                  <text x={left - 8} y={y(value) + 3} textAnchor="end">
                    {value.toLocaleString(undefined, {
                      notation: "compact",
                      maximumFractionDigits: 1,
                    })}
                  </text>
                </g>
              );
            })}
            <text x={left} y={height - 8}>
              {format(panel.time.from)}
            </text>
            <text x={right} y={height - 8} textAnchor="end">
              {format(panel.time.to)}
            </text>
            <text x={left} y="12">
              {points[0]?.unit}
            </text>
          </g>
        ) : null}
        {series.map((s) => (
          <g key={s.label}>
            {s.evidence.values.map((point, index) => {
              const previous = s.evidence.values[index - 1],
                disconnected =
                  !previous ||
                  point.gap ||
                  previous.gap ||
                  Date.parse(point.at!) - Date.parse(previous.at!) > 900000;
              const label = `${s.label}: ${point.value.toLocaleString(undefined, { maximumFractionDigits: 2 })} ${point.unit} · ${format(point.at!)}`;
              return (
                <g
                  key={index}
                  tabIndex={0}
                  role="img"
                  aria-label={label}
                  onFocus={() => setFocused(label)}
                  onBlur={() => setFocused("")}
                  onMouseEnter={() => setFocused(label)}
                  onMouseLeave={() => setFocused("")}
                >
                  {!disconnected && widget.presentation.visualization === "area" ? (
                    <path
                      d={`M${x(previous.at!)} ${y(Math.max(min, Math.min(0, max)))} L${x(previous.at!)} ${y(previous.value)} L${x(point.at!)} ${y(point.value)} L${x(point.at!)} ${y(Math.max(min, Math.min(0, max)))} Z`}
                      fill={s.color}
                      opacity=".09"
                    />
                  ) : null}
                  {!disconnected ? (
                    <line
                      x1={x(previous.at!)}
                      y1={y(previous.value)}
                      x2={x(point.at!)}
                      y2={y(point.value)}
                      stroke={s.color}
                      strokeWidth="2"
                      vectorEffect="non-scaling-stroke"
                    />
                  ) : null}
                  <circle cx={x(point.at!)} cy={y(point.value)} r="3" fill={s.color} />
                  <title>{label}</title>
                </g>
              );
            })}
          </g>
        ))}
      </svg>
      {widget.presentation.showLegend === true ? (
        <ul className="dashboard-diagram-legend">
          {series.map((s) => (
            <li key={s.label} title={s.label}>
              <span
                className="dashboard-diagram-swatch"
                style={{ background: s.color }}
                aria-hidden="true"
              />
              {s.label.split(" · ")[0].replace(/^DEMO /, "")}
            </li>
          ))}
        </ul>
      ) : null}
      {focused ? <output className="dashboard-series-inspection">{focused}</output> : null}
    </div>
  );
}
