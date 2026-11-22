import type { RuleCompilation } from "./configurableRuleApi";

export function RuleDependencyComparison({
  previous,
  current,
  title,
  open,
}: {
  previous: RuleCompilation;
  current: RuleCompilation;
  title: string;
  open?: boolean;
}) {
  const prior = previous.checkpoint;
  const next = current.checkpoint;
  const aliases = new Set([...Object.keys(prior.references), ...Object.keys(next.references)]);
  const changes = [
    ["Rule definition", prior.documentDigest, next.documentDigest],
    ["Compiled rule", prior.compilationDigest, next.compilationDigest],
    ["Source checkpoint", prior.source.digest, next.source.digest],
    ...Array.from(aliases, (alias) => [
      `Reference ${alias}`,
      prior.references[alias]?.manifestDigest,
      next.references[alias]?.manifestDigest,
    ]),
  ].filter(([, from, to]) => from !== to);
  return (
    <details open={open}>
      <summary>{title}</summary>
      {changes.length ? (
        <dl>
          {changes.map(([label, from, to]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>
                {from ?? "Absent"} → {to ?? "Absent"}
              </dd>
            </div>
          ))}
        </dl>
      ) : (
        <p>The rule, source checkpoint and reference digests match.</p>
      )}
      {previous.bundleDigest !== current.bundleDigest ? <p>The bundle digest changed.</p> : null}
    </details>
  );
}
