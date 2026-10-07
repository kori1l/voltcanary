export function TimeDisplay({ value }: { value: string | null | undefined }) {
  const parsed = value ? new Date(value) : null;
  if (!parsed || Number.isNaN(parsed.getTime())) return <span>Unknown time</span>;
  return <time dateTime={parsed.toISOString()} title={parsed.toISOString()}>{new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(parsed)}</time>;
}

export function TimezoneNote() {
  return <small className="field-hint">Times in {Intl.DateTimeFormat().resolvedOptions().timeZone}. Locale affects formatting only.</small>;
}
