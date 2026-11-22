import type { LatestPosition } from "./positionApi";

export function VehicleCurrentSpeed({
  latest,
  unavailable = false,
}: {
  latest: LatestPosition | undefined;
  unavailable?: boolean;
}) {
  const speed = latest?.position?.speedKmh;
  if (unavailable || latest?.freshness !== "FRESH" || speed == null)
    return <span>Текущая скорость недоступна</span>;
  return (
    <span>Скорость: {speed.toLocaleString(undefined, { maximumFractionDigits: 1 })} km/h</span>
  );
}
