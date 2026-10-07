import { formatNumber } from "./deviceDetailsFormatting";
export function DeviceMetricCard({
  label,
  value,
  unit = "",
  valueText,
  emphasis = false,
}: {
  label: string;
  value?: number;
  unit?: string;
  valueText?: string;
  emphasis?: boolean;
}) {
  return (
    <article className={`metric-card${emphasis ? " metric-card-emphasis" : ""}`}>
      <span>{label}</span>
      <strong>{valueText ?? (value === undefined ? "No data" : `${formatNumber(value)}${unit ? ` ${unit}` : ""}`)}</strong>
    </article>
  );
}

