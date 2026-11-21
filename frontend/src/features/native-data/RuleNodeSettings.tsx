type SchemaValue = { type?: unknown; enum?: unknown; minimum?: number; maximum?: number; maxLength?: number };
export type NodeSettingsSchema = { properties?: Record<string, SchemaValue>; required?: string[] };

export function RuleNodeSettings({ schema, json, onChange }: {
  schema?: NodeSettingsSchema; json: string; onChange: (json: string) => void;
}) {
  const settings = parseSettings(json);
  const properties = Object.entries(schema?.properties ?? {});
  const scalar = properties.filter(([, property]) => isScalar(property));
  const hasComplexSettings = properties.length > scalar.length;

  function set(field: string, value: unknown) {
    if (!settings) return;
    const next = { ...settings };
    if (value === undefined) delete next[field];
    else next[field] = value;
    onChange(JSON.stringify(next, null, 2));
  }

  return <>
    {settings ? scalar.map(([field, property]) => {
      const required = schema?.required?.includes(field);
      const value = settings[field];
      const label = fieldLabel(field);
      const choices = stringChoices(property);
      if (choices || property.type === "boolean") {
        const options = choices ?? ["true", "false"];
        return <label key={field}>{label}<select required={required} value={String(value ?? "")} onChange={event => {
          const selected = event.target.value;
          set(field, selected === "" ? undefined : property.type === "boolean" ? selected === "true" : selected);
        }}>
          <option value="">Select value</option>
          {options.map(item => <option key={item}>{item}</option>)}
        </select></label>;
      }
      const numeric = property.type === "integer";
      return <label key={field}>{label}<input required={required} type={numeric ? "number" : "text"}
        min={property.minimum} max={property.maximum} step={numeric ? 1 : undefined} maxLength={property.maxLength}
        value={typeof value === "number" || typeof value === "string" ? value : ""} onChange={event => {
          const input = event.target.value;
          set(field, input === "" ? undefined : numeric ? Number(input) : input);
        }}/></label>;
    }) : <p role="status">Correct the JSON to use the parameter fields.</p>}
    <details open={!settings || hasComplexSettings || undefined}>
      <summary>Node settings JSON</summary>
      {hasComplexSettings ? <p>Use JSON for expressions and other structured parameters.</p> : null}
      <label>Node settings (JSON)<textarea required rows={4} value={json} onChange={event => onChange(event.target.value)}/></label>
    </details>
  </>;
}

function fieldLabel(name: string) {
  const label = name.replace(/([a-z0-9])([A-Z])/g, "$1 $2").replace(/_/g, " ");
  return label.charAt(0).toUpperCase() + label.slice(1);
}

function stringChoices(property: SchemaValue): string[] | undefined {
  return Array.isArray(property.enum) && property.enum.every(value => typeof value === "string") ? property.enum : undefined;
}

function isScalar(property: SchemaValue) {
  return property.type === "string" || property.type === "integer" || property.type === "boolean" || Boolean(stringChoices(property));
}

function parseSettings(json: string): Record<string, unknown> | null {
  try {
    const settings: unknown = JSON.parse(json, (_key, value: unknown) => {
      if (typeof value === "number" && (!Number.isFinite(value) || Number.isInteger(value) && !Number.isSafeInteger(value))) {
        throw new Error("Invalid numeric setting");
      }
      return value;
    });
    return settings && typeof settings === "object" && !Array.isArray(settings) ? settings as Record<string, unknown> : null;
  } catch {
    return null;
  }
}
