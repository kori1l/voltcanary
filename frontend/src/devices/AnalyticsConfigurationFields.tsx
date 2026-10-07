export function AnalyticsConfigurationFields({ text, onChange }: { text: string; onChange: (value: string) => void }) {
  let config: Record<string, unknown> = {};
  let valid = true;
  try { const parsed: unknown = JSON.parse(text); if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) valid = false; else config = parsed as Record<string, unknown>; } catch { valid = false; }
  return <div className="form-stack">
    <label>Baseline window (days)<input type="number" min="1" step="1" disabled={!valid} value={typeof config.baselineWindowDays === "number" ? config.baselineWindowDays : ""} onChange={(event) => {
      const next = { ...config }; if (!event.target.value) delete next.baselineWindowDays; else next.baselineWindowDays = Number(event.target.value);
      onChange(JSON.stringify(next, null, 2));
    }} /><small>Device analytics configuration. Other keys are preserved when this field changes.</small></label>
    <details><summary>Advanced configuration JSON</summary><label>Analytics JSON<textarea className="csv-textarea" value={text} onChange={(event) => onChange(event.target.value)} /></label></details>
    {!valid ? <p className="error-message" role="alert">Enter a JSON object before editing known fields or saving.</p> : null}
  </div>;
}
