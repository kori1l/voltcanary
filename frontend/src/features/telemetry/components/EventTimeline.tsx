import { TelemetryEvent } from "../model/telemetry.types";
import { formatShortDateTime } from "../model/telemetry.utils";

export function EventTimeline({
  events,
  selectedEventId,
  onSelectEvent,
}: {
  events: TelemetryEvent[];
  selectedEventId?: string | null;
  onSelectEvent: (eventId: string) => void;
}) {
  return (
    <section className="telemetry-timeline">
      <div className="section-header">
        <div>
          <h2>Event timeline</h2>
          <p>{events.length > 0 ? `${events.length} events in selected range.` : "No threshold or stale-data events."}</p>
        </div>
      </div>
      {events.length > 0 ? (
        <ol>
          {events.slice(0, 7).map((event) => (
            <li key={event.id}>
              <button
                className={`timeline-event timeline-event-${event.severity}${selectedEventId === event.id ? " timeline-event-selected" : ""}`}
                type="button"
                onClick={() => onSelectEvent(event.id)}
              >
                <span>{formatShortDateTime(event.timestamp)}</span>
                <strong>{event.title}</strong>
                <em>{event.value !== undefined ? event.value.toLocaleString(undefined, { maximumFractionDigits: 1 }) : "-"}</em>
                <small>{event.metricKey ?? "device"}</small>
              </button>
            </li>
          ))}
          {events.length > 7 ? <li className="timeline-more">+{events.length - 7} more events. Open a metric to inspect details.</li> : null}
        </ol>
      ) : (
        <div className="empty-state">
          <strong>No events in selected range</strong>
          <span>Charts still show raw telemetry, but no threshold or stale-data events were detected.</span>
        </div>
      )}
    </section>
  );
}
