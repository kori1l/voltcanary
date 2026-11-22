import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
export function DashboardCostEvidence({
  widget,
  data,
}: {
  widget: DashboardWidget;
  data: Record<string, unknown>;
}) {
  const estimate = (data.estimate ?? {}) as Record<string, unknown>;
  const amount =
    typeof estimate.amount === "number" && Number.isFinite(estimate.amount)
      ? estimate.amount.toLocaleString(undefined, { maximumFractionDigits: 3 })
      : "Unavailable";
  return (
    <>
      <p className="dashboard-primary-value">
        {amount} <span>{String(estimate.currency ?? "")}</span>
      </p>
      <span className="dashboard-state-badge">Estimate</span>
      {estimate.coverage !== "COMPLETE" ? (
        <span className="dashboard-state-badge">{String(estimate.coverage ?? "UNKNOWN")}</span>
      ) : null}
      {widget.presentation.showCalculation === true ? (
        <p>
          Qualified energy × versioned flat tariff. Fixed fees and exchange conversion are excluded.
          Tax policy is recorded per segment. This is an estimate, not an invoice.
        </p>
      ) : null}
    </>
  );
}
