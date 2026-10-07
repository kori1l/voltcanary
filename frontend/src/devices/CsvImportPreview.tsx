import { useMemo, useState } from "react";
import { downloadText } from "./details/deviceDetailsFormatting";

export function inspectImportCsv(text: string, columns: string[]) {
  const lines = text.replace(/^\uFEFF/, "").split(/\r?\n/);
  const errors: string[] = [];
  if (lines[0]?.trim() !== columns.join(",")) errors.push(`Header must be ${columns.join(",")}`);
  const rows = lines.slice(1).flatMap((line, index) => {
    if (!line.trim()) return [];
    const values = line.split(",");
    const rowErrors: string[] = [];
    if (values.length !== columns.length) rowErrors.push(`expected ${columns.length} columns`);
    if (line.includes('"')) rowErrors.push("quoted fields are unsupported by the current import API");
    if (!values[0]?.trim()) rowErrors.push(`${columns[0]} is required`);
    if (!values[1]?.trim()) rowErrors.push(`${columns[1]} is required`);
    return [{ line: index + 2, values, errors: rowErrors }];
  });
  if (!rows.length) errors.push("Add at least one data row");
  return { rows, errors, valid: errors.length === 0 && rows.every((row) => !row.errors.length) };
}

export function CsvImportPreview({ text, onChange, columns, example, busy = false, onValidityChange }: {
  text: string; onChange: (value: string) => void; columns: string[]; example: string;
  busy?: boolean; onValidityChange?: (valid: boolean) => void;
}) {
  const [fileError, setFileError] = useState<string | null>(null);
  const preview = useMemo(() => inspectImportCsv(text, columns), [text, columns]);
  // Callers use the same pure validator before submitting; preview never changes the payload.
  void onValidityChange;
  return <div className="form-stack">
    <p>Preview validates the current API format locally. Import sends these contents unchanged. The server may accept some rows and reject others; review its result before retrying.</p>
    <button type="button" className="ghost-button" onClick={() => downloadText(`${columns.join(",")}\n${example}\n`, "import-template.csv", "text/csv")}>Download CSV template</button>
    <label>CSV file<input type="file" accept=".csv,text/csv" disabled={busy} onChange={async (event) => {
      const file = event.target.files?.[0]; if (!file) return;
      try { if (file.size > 2_000_000) throw new Error("Choose a CSV smaller than 2 MB"); onChange(await file.text()); setFileError(null); }
      catch (error) { setFileError(error instanceof Error ? error.message : "Cannot read file"); }
    }} /></label>
    {fileError ? <p className="error-message" role="alert">{fileError}</p> : null}
    <details><summary>Advanced: paste or edit CSV</summary><label>CSV contents<textarea className="csv-textarea" disabled={busy} value={text} onChange={(event) => onChange(event.target.value)} /></label></details>
    {preview.errors.map((error) => <p className="status-note" key={error}>{error}</p>)}
    {preview.rows.length ? <div className="console-table-region" role="region" aria-label="CSV preview" tabIndex={0}><table><thead><tr><th>Line</th>{columns.map((column) => <th key={column}>{column}</th>)}<th>Validation</th></tr></thead><tbody>{preview.rows.slice(0, 50).map((row) => <tr key={row.line}><td>{row.line}</td>{columns.map((_, index) => <td key={index}>{row.values[index] || "—"}</td>)}<td>{row.errors.join("; ") || "Format valid"}</td></tr>)}</tbody></table><p>{preview.rows.length} rows. Showing first 50. Server checks references and permissions.</p></div> : null}
  </div>;
}
