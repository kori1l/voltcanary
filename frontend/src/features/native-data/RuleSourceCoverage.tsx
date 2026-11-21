import { useState } from "react";
import { NativeCircleBrowser } from "./NativeCircleBrowser";

export type RuleCoverageDraft = { from: string; boundary: string; proofId: string; circles: { id: string; revision: number }[] };
export const emptyRuleCoverage: RuleCoverageDraft = { from: "", boundary: "", proofId: "", circles: [] };
const instant = "[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,6})?Z";
const uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";

export function RuleSourceCoverage({ value, onChange }: { value: RuleCoverageDraft; onChange: (next: RuleCoverageDraft) => void }) {
  const [browse, setBrowse] = useState(false);
  const selected = new Set(value.circles.map(circle => circle.id));
  return <fieldset className="form-stack">
    <legend>Source coverage</legend>
    <p>The source binding and schema revision come from the rule's Read stream node.</p>
    <label>Coverage starts (UTC)<input required pattern={instant} value={value.from}
      onChange={event => onChange({ ...value, from: event.target.value })}/></label>
    <label>Activation boundary (UTC)<input required pattern={instant} value={value.boundary}
      onChange={event => onChange({ ...value, boundary: event.target.value })}/></label>
    <label>Coverage proof ID<input required pattern={uuid} value={value.proofId}
      onChange={event => onChange({ ...value, proofId: event.target.value })}/></label>
    <button className="ghost-button" type="button" aria-expanded={browse} disabled={value.circles.length >= 16}
      onClick={() => setBrowse(open => !open)}>{browse ? "Hide zone catalog" : "Choose a zone"}</button>
    {browse ? <NativeCircleBrowser onSelect={circle => {
      if (!selected.has(circle.id)) onChange({ ...value, circles: [...value.circles, { id: circle.id, revision: circle.revision }] });
      setBrowse(false);
    }}/> : null}
    {value.circles.length ? <ul>{value.circles.map(circle => <li key={circle.id}>
      {circle.id} · Revision {circle.revision} <button className="ghost-button" type="button"
        onClick={() => onChange({ ...value, circles: value.circles.filter(item => item.id !== circle.id) })}>Remove zone</button>
    </li>)}</ul> : null}
  </fieldset>;
}
