import { useEffect, useRef, useState, type FormEvent } from "react";
import { previewRuleSession, previewRuleWindow, type RuleCompilation } from "./configurableRuleApi";

export type PreviewNode = { id: string; zone?: boolean };
type PreviewResult =
  | { kind: "window"; response: Awaited<ReturnType<typeof previewRuleWindow>> }
  | { kind: "session"; response: Awaited<ReturnType<typeof previewRuleSession>> };

export function ConfigurableRuleWindowPreview({ compilation, account, nodes, circles, kind }: {
  compilation: RuleCompilation; account: string; nodes: PreviewNode[]; circles?: string[]; kind: "window" | "session";
}) {
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState<PreviewResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [node, setNode] = useState(nodes[0].id);
  const zone = nodes.find(candidate => candidate.id === node)?.zone;
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);

  async function preview(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    const form = new FormData(event.currentTarget);
    const field = (name: string) => String(form.get(name) ?? "").trim();
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    setError(null);
    setResult(null);
    try {
      const window = { account, node, subjectLiteral: field("subject"), from: field("from"), until: field("until") };
      const outcome: PreviewResult = kind === "session" ? { kind, response: await previewRuleSession(compilation, {
        ...window, occurrenceLiteral: field("occurrence"), circle: zone ? field("circle") : null, proof: field("proof") || null,
      }, controller.signal) } : { kind, response: await previewRuleWindow(compilation, window, controller.signal) };
      if (!controller.signal.aborted) setResult(outcome);
    } catch (failure) {
      if (!controller.signal.aborted) setError((failure as Error).message);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  const title = kind === "session" ? "Rule session dry run" : "Rule window dry run";
  const verdict = result?.kind === "window" ? result.response.result : result?.response;
  return <section aria-label={title}>
    <h3>{title}</h3>
    <form onSubmit={event => void preview(event)} onChange={() => { setError(null); setResult(null); }}>
      <fieldset className="form-stack" disabled={busy}>
        <legend>Evaluation window</legend>
        <label>Rule node<select value={node} onChange={event => setNode(event.target.value)}>
          {nodes.map(({ id }) => <option key={id}>{id}</option>)}
        </select></label>
        <label>Subject (VCL literal)<input name="subject" required placeholder='{"type":"TEXT","value":"asset-1"}'/></label>
        <label>From (ISO timestamp)<input name="from" required/></label>
        <label>Until (ISO timestamp)<input name="until" required/></label>
        {kind === "session" ? <>
          <label>Occurrence (VCL literal)<input name="occurrence" required/></label>
          <label>Coverage proof ID (optional)<input name="proof"/></label>
          {zone ? <label>Reviewed circle<select name="circle" required defaultValue="">
            <option value="">Select circle</option>
            {circles?.map(id => <option key={id}>{id}</option>)}
          </select></label> : null}
        </> : null}
        <button type="submit" className="primary-button" disabled={zone && !circles?.length}>Run preview</button>
      </fieldset>
    </form>
    {busy ? <p role="status">Evaluating recorded observations…</p> : null}
    {error ? <p role="alert">{error}</p> : null}
    {result && verdict ? <div role="status">
      <p>{verdict.status}{verdict.reason ? ` · ${verdict.reason}` : ""}</p>
      {verdict.literal == null ? null : <pre>{JSON.stringify(verdict.literal, null, 2)}</pre>}
      <p>Evidence references: {verdict.evidence.length}</p>
      <details><summary>Evidence details</summary>
        {result.kind === "window" ? <>
          <p>Source cut digest: {result.response.sourceCutDigest}</p>
          {Object.entries(result.response.result.referenceManifestDigests).map(([name, digest]) => <p key={name}>{name}: {digest}</p>)}
        </> : <SessionEvidence session={result.response}/>}
        {verdict.evidence.length ? <ul>{verdict.evidence.map(id => <li key={id}>{id}</li>)}</ul> : null}
      </details>
    </div> : null}
  </section>;
}

function SessionEvidence({ session }: { session: Awaited<ReturnType<typeof previewRuleSession>> }) {
  return <dl>
    {session.sessionWindow ? <><dt>Session window</dt><dd>{session.sessionWindow.startInclusive} to {session.sessionWindow.endExclusive}</dd></> : null}
    <dt>Entry observation</dt><dd>{session.entry ?? "Unavailable"}</dd>
    <dt>Exit observation</dt><dd>{session.exit ?? "Unavailable"}</dd>
    <dt>First witness</dt><dd>{session.firstWitness ?? "Unavailable"}</dd>
    <dt>Last witness</dt><dd>{session.lastWitness ?? "Unavailable"}</dd>
    <dt>Position witnesses</dt><dd>{session.positionWitnesses.join(", ") || "None"}</dd>
    <dt>Coverage digest</dt><dd>{session.coverageDigest ?? "Unavailable"}</dd>
    <dt>Observation checkpoint</dt><dd>{session.checkpoint}</dd>
  </dl>;
}
