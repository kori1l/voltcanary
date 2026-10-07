export function readPreviewRows(text: string, kind: "csv" | "jsonl") {
  if (kind === "jsonl") {
    const rows = text.split(/\r?\n/).filter((line) => line.trim()).slice(0, 20).map((line, index) => {
      try { const value: unknown = JSON.parse(line); if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error(); return value as Record<string, unknown>; }
      catch { throw new Error(`JSONL line ${index + 1} must contain an object`); }
    });
    return { columns: [...new Set(rows.flatMap(Object.keys))], rows };
  }
  const records: string[][] = []; let record: string[] = []; let field = ""; let quoted = false;
  for (let index = 0; index < text.length && records.length < 21; index++) {
    const char = text[index];
    if (char === '"') { if (quoted && text[index + 1] === '"') { field += '"'; index++; } else quoted = !quoted; }
    else if (char === "," && !quoted) { record.push(field); field = ""; }
    else if (char === "\n" && !quoted) { record.push(field.replace(/\r$/, "")); records.push(record); record = []; field = ""; }
    else field += char;
  }
  if (quoted) throw new Error("Incomplete quoted CSV field in preview");
  if (field || record.length) { record.push(field.replace(/\r$/, "")); records.push(record); }
  const columns = (records.shift() ?? []).map((name) => name.replace(/^\uFEFF/, ""));
  if (!columns.length || new Set(columns).size !== columns.length || columns.some((name) => !name)) throw new Error("CSV requires unique nonempty column names");
  return { columns, rows: records.filter((row) => row.some(Boolean)).map((row, index) => { if (row.length !== columns.length) throw new Error(`CSV preview row ${index + 2} has ${row.length} columns; expected ${columns.length}`); return Object.fromEntries(columns.map((column, index) => [column, row[index]])); }) };
}
