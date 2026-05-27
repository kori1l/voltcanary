export function RecommendationPanel({
  summary,
  possibleCause,
  recommendedAction,
}: {
  summary?: string;
  possibleCause?: string;
  recommendedAction?: string;
}) {
  return (
    <section className="analysis-panel recommendation-analysis">
      <h3>Analysis</h3>
      <p>{summary ?? "No analysis is available for the selected event."}</p>
      {possibleCause ? (
        <div>
          <strong>Likely reason</strong>
          <span>{possibleCause}</span>
        </div>
      ) : null}
      {recommendedAction ? (
        <div>
          <strong>Recommended action</strong>
          <span>{recommendedAction}</span>
        </div>
      ) : null}
    </section>
  );
}
