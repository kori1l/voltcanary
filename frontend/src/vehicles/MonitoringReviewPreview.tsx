import type { MonitoringReview } from "./monitoringApi";
import { VehicleMap } from "./VehicleMap";
import type { VehicleZone } from "./monitoringModel";

const describe = (zone: VehicleZone) =>
  `${zone.name}: ${zone.latitude}, ${zone.longitude} · ${zone.radiusMetres} m`;
export function MonitoringReviewPreview({ review }: { review: MonitoringReview }) {
  const before = review.before,
    after = review.after;
  return (
    <section className="form-stack" aria-label="Monitoring changes">
      <h3>Review monitoring changes</h3>
      <p>
        Speed limit: {before.speedLimitKmh} → {after.speedLimitKmh} km/h · Trip idle threshold:{" "}
        {before.idleMinutes} → {after.idleMinutes} min
      </p>
      <ul>
        {after.zones.map((zone) => {
          const old = before.zones.find((item) => item.id === zone.id);
          return (
            <li key={zone.id}>
              {!old ? "Add" : JSON.stringify(old) === JSON.stringify(zone) ? "Keep" : "Change"}:{" "}
              {old && JSON.stringify(old) !== JSON.stringify(zone) ? `${describe(old)} → ` : ""}
              {describe(zone)}
            </li>
          );
        })}
        {before.zones
          .filter((zone) => !after.zones.some((item) => item.id === zone.id))
          .map((zone) => (
            <li key={zone.id}>Remove: {describe(zone)}</li>
          ))}
      </ul>
      {before.zones.length ? (
        <details>
          <summary>Current zones</summary>
          <VehicleMap evidence={null} zones={before.zones} zoneEditing showDetails={false} />
        </details>
      ) : null}
      {after.zones.length ? (
        <div>
          <p>Proposed zones</p>
          <VehicleMap evidence={null} zones={after.zones} zoneEditing showDetails={false} />
        </div>
      ) : null}
      <p>
        Revision {review.baseRevision} ·{" "}
        {review.status === "SUCCEEDED"
          ? `Saved revision ${review.appliedRevision}`
          : `Review expires ${new Date(review.expiresAt).toLocaleString()}`}
        . These settings do not activate background alerts or notifications.
      </p>
    </section>
  );
}
