import type { Literal } from "../../native-data/nativeDataApi";
import { displayLiteral } from "../../native-data/nativeDocuments";

type RuleWindowResult = {
  status: string;
  reason?: string | null;
  from: string;
  until: string;
  literal?: Literal | null;
  evidence: string[];
  supersedes?: string | null;
};

export function DashboardRuleWindowResult({ value }: { value: unknown }) {
  const result = value as RuleWindowResult | undefined;
  if (!result || !Array.isArray(result.evidence))
    return <p role="status">Saved rule result unavailable.</p>;
  return (
    <section aria-label="Saved rule window">
      <p>
        <time dateTime={result.from}>{result.from}</time> to{" "}
        <time dateTime={result.until}>{result.until}</time>
      </p>
      <p role="status">
        {result.status}
        {result.reason ? ` · ${result.reason}` : ""}
      </p>
      {result.literal ? <p>{displayLiteral(result.literal)}</p> : null}
      {result.evidence.length ? (
        <details>
          <summary>Observation references ({result.evidence.length})</summary>
          <ul>
            {result.evidence.map((id) => (
              <li key={id}>{id}</li>
            ))}
          </ul>
        </details>
      ) : (
        <p>No observation references available.</p>
      )}
      {result.supersedes ? <p>Supersedes measure {result.supersedes}</p> : null}
    </section>
  );
}
