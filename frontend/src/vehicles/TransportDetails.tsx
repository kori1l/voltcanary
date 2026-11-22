import type { TransportContext } from "./positionApi";

export function TransportDetails({ context }: { context: TransportContext | null | undefined }) {
  if (!context) return null;
  const fields = [
    ["Route", context.routeShortName ?? context.routeId],
    ["Direction", context.direction],
    ["Destination", context.destination],
    ["Next stop ID", context.nextStopId],
    [
      "Schedule offset",
      context.scheduleDeviationSeconds == null ? null : `${context.scheduleDeviationSeconds} s`,
    ],
    ["Doors", context.doorsOpen == null ? null : context.doorsOpen ? "Open" : "Closed"],
    ["Acceleration", context.accelerationMs2 == null ? null : `${context.accelerationMs2} m/s²`],
    ["Scheduled start (Helsinki)", context.scheduledStart],
    ["Location source", context.locationSource],
  ];
  return (
    <dl className="transport-details" aria-label="Observed journey information">
      {fields
        .filter(([, value]) => value != null)
        .map(([label, value]) => (
          <div key={label}>
            <dt>{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
    </dl>
  );
}
