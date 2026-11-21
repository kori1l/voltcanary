import type { AgentPlan } from "./aiAgentApi";

export function sourceMappingExample(action: AgentPlan["actions"][number]) {
  const { mappingExample: example, conversion, fieldId, bindingId, schemaRevision } = action.parameters;
  if (typeof fieldId !== "string" || !fieldId || typeof bindingId !== "string" || !bindingId
      || typeof schemaRevision !== "number" || !Number.isSafeInteger(schemaRevision) || schemaRevision < 1
      || typeof conversion !== "object" || conversion === null || !("method" in conversion) || conversion.method !== "parse_boolean"
      || !("trueTokens" in conversion) || !tokens(conversion.trueTokens)
      || !("falseTokens" in conversion) || !tokens(conversion.falseTokens)) return null;
  if (typeof example !== "object" || example === null || !("sample" in example) || typeof example.sample !== "string"
      || !("literal" in example) || typeof example.literal !== "object" || example.literal === null
      || !("type" in example.literal) || example.literal.type !== "BOOLEAN"
      || !("value" in example.literal) || typeof example.literal.value !== "boolean") return null;
  const { trueTokens, falseTokens } = conversion;
  if (trueTokens.some(token => falseTokens.includes(token))
      || !(example.literal.value ? trueTokens : falseTokens).includes(example.sample)) return null;
  return { sample: example.sample, value: example.literal.value, fieldId, bindingId, schemaRevision, trueTokens, falseTokens };
}

function tokens(value: unknown): value is string[] {
  return Array.isArray(value) && value.length > 0 && value.length <= 8
    && value.every(token => typeof token === "string" && token.length > 0)
    && new Set(value).size === value.length;
}

export function SourceMappingPreview({ action }: { action: AgentPlan["actions"][number] }) {
  const example = sourceMappingExample(action);
  if (!example) return <p role="alert">Пример преобразования недоступен.</p>;
  return <section aria-label="Предпросмотр маппинга">
    <dl>
      <dt>Привязка источника</dt><dd><code>{example.bindingId}</code> · ревизия {example.schemaRevision}</dd>
      <dt>Поле</dt><dd><code>{example.fieldId}</code></dd>
      <dt>Значения для true</dt><dd><code>{example.trueTokens.map(token => JSON.stringify(token)).join(", ")}</code></dd>
      <dt>Значения для false</dt><dd><code>{example.falseTokens.map(token => JSON.stringify(token)).join(", ")}</code></dd>
    </dl>
    <p>Пример преобразования <code>{JSON.stringify(example.sample)}</code> → <code>{String(example.value)}</code></p>
  </section>;
}
