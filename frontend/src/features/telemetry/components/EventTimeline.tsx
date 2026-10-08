import { TelemetryEvent } from "../model/telemetry.types";
import { useState } from "react";
import { formatShortDateTime } from "../model/telemetry.utils";

export function EventTimeline({
  events,
  selectedEventId,
  onSelectEvent,
  incompleteEvidence = false,
}: {
  events: TelemetryEvent[];
  selectedEventId?: string | null;
  onSelectEvent: (eventId: string) => void;
  incompleteEvidence?: boolean;
}) {
  const scope = events.map((event) => `${event.id}:${event.timestamp}`).join("|");
  const [position, setPosition] = useState({ scope, offset: 0 });
  const offset = position.scope === scope ? position.offset : 0;
  return (
    <section className="telemetry-timeline">
      <div className="section-header">
        <div>
          <h2>Event timeline</h2>
          <p>{events.length > 0 ? `${events.length} loaded events in selected range.` : incompleteEvidence ? "No recorded events loaded." : "No threshold or stale-data events."}</p>
          {incompleteEvidence ? <p>Envelope ranges do not enumerate exact threshold transitions or their order. Review raw values for those details; event counts cover loaded analytics evidence.</p> : null}
        </div>
      </div>
      {events.length > 0 ? (
        <ol>
          {events.slice(offset, offset + 7).map((event) => (
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
        </ol>
      ) : (
        <div className="empty-state">
          <strong>{incompleteEvidence ? "No recorded events loaded" : "No events in selected range"}</strong>
          <span>{incompleteEvidence ? "Envelope extrema remain readable; absence of recorded events does not prove absence of threshold transitions." : "Charts still show raw telemetry, but no threshold or stale-data events were detected."}</span>
        </div>
      )}
      {events.length > 7 ? <div className="action-row"><button type="button" className="ghost-button" disabled={!offset} onClick={() => setPosition({ scope, offset: Math.max(0, offset - 7) })}>Previous events</button><span>Events {offset + 1}–{Math.min(offset + 7, events.length)} of {events.length} loaded events</span><button type="button" className="ghost-button" disabled={offset + 7 >= events.length} onClick={() => setPosition({ scope, offset: offset + 7 })}>Next events</button></div> : null}
    </section>
  );
}
