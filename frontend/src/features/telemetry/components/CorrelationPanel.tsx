import { RelatedSignal } from "../model/telemetry.types";

export function CorrelationPanel({ relatedSignals }: { relatedSignals: RelatedSignal[] }) {
  return (
    <section className="analysis-panel">
      <h3>Related signals</h3>
      {relatedSignals.length > 0 ? (
        <ul className="related-signals">
          {relatedSignals.map((signal) => (
            <li key={signal.metricKey}>
              <strong>{signal.name}</strong>
              <span className={`correlation-pill correlation-${signal.correlation}`}>{signal.correlation}</span>
              <p>{signal.note}</p>
            </li>
          ))}
        </ul>
      ) : (
        <p className="muted-text">No related signals are available.</p>
      )}
    </section>
  );
}
