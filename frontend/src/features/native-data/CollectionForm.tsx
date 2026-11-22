import { useState, type FormEvent } from "react";
import {
  businessKeyTypes,
  enumChoices,
  newField,
  typeLabels,
  type CollectionDraft,
} from "./nativeDocuments";
import type { Summary } from "./nativeDataApi";

export function CollectionForm({
  busy,
  types,
  tables,
  resourceKinds,
  onPrepare,
  onCancel,
}: {
  busy: boolean;
  types: string[];
  tables: Summary[];
  resourceKinds: string[];
  onPrepare: (draft: CollectionDraft) => void;
  onCancel: () => void;
}) {
  const [draft, setDraft] = useState<CollectionDraft>({
    label: "",
    fields: [newField("field0")],
    history: false,
  });
  const [sequence, setSequence] = useState(1);
  const invalidChoices = draft.fields.some(
    (field) => field.type === "ENUM" && choiceError(field.choices),
  );
  function submit(event: FormEvent) {
    event.preventDefault();
    if (!invalidChoices) onPrepare(draft);
  }
  return (
    <form className="panel native-editor" onSubmit={submit}>
      <h2>Create table</h2>
      <fieldset disabled={busy}>
        <label>
          Table name
          <input
            required
            maxLength={256}
            value={draft.label}
            onChange={(event) => setDraft({ ...draft, label: event.target.value })}
          />
        </label>
        <div className="native-fields">
          {draft.fields.map((field, index) => {
            const update = (patch: Partial<typeof field>) =>
              setDraft({
                ...draft,
                fields: draft.fields.map((item) =>
                  item.key === field.key ? { ...item, ...patch } : item,
                ),
              });
            const complex = field.type === "INTERVAL" || field.type === "POINT";
            return (
              <section className="native-field" key={field.key} aria-label={`Column ${index + 1}`}>
                <label>
                  Column name
                  <input
                    required
                    aria-label={`Column ${index + 1} name`}
                    maxLength={256}
                    value={field.label}
                    onChange={(event) => update({ label: event.target.value })}
                  />
                </label>
                <label>
                  Type
                  <select
                    aria-label={`Column ${index + 1} type`}
                    value={field.type}
                    onChange={(event) => {
                      const type = event.target.value;
                      update({
                        type,
                        indexed: type !== "INTERVAL" && type !== "POINT" && field.indexed,
                        businessKey: businessKeyTypes.includes(type) && field.businessKey,
                      });
                    }}
                  >
                    {types.map((type) => (
                      <option key={type} value={type}>
                        {typeLabels[type] ?? type}
                      </option>
                    ))}
                  </select>
                </label>
                {field.type === "REF" ? (
                  <label>
                    Reference target
                    <select
                      required
                      value={
                        field.reference?.kind === "RESOURCE"
                          ? "resource:" + field.reference.resourceKind
                          : field.reference?.kind === "DATASET"
                            ? "table:" + field.reference.datasetId
                            : field.reference?.kind === "SELF"
                              ? "self"
                              : ""
                      }
                      onChange={(event) => {
                        const value = event.target.value;
                        update({
                          reference:
                            value === "self"
                              ? { kind: "SELF" }
                              : value.startsWith("resource:")
                                ? { kind: "RESOURCE", resourceKind: value.slice(9) }
                                : value.startsWith("table:")
                                  ? { kind: "DATASET", datasetId: value.slice(6) }
                                  : undefined,
                        });
                      }}
                    >
                      <option value="">Select target</option>
                      <optgroup label="Tables">
                        <option value="self">This table</option>
                        {tables.map((table) => (
                          <option key={table.id} value={"table:" + table.id}>
                            {table.label}
                          </option>
                        ))}
                      </optgroup>
                      <optgroup label="Resources">
                        {resourceKinds.map((kind) => (
                          <option key={kind} value={"resource:" + kind}>
                            {kind.toLowerCase().replace(/_/g, " ")}
                          </option>
                        ))}
                      </optgroup>
                    </select>
                  </label>
                ) : null}
                {field.type === "ENUM" ? (
                  <label>
                    Allowed values (one per line)
                    <textarea
                      required
                      value={field.choices ?? ""}
                      onChange={(event) => update({ choices: event.target.value })}
                    />
                    {field.choices && choiceError(field.choices) ? (
                      <small>{choiceError(field.choices)}</small>
                    ) : null}
                  </label>
                ) : null}
                {["INT64", "DECIMAL"].includes(field.type) ? (
                  <label>
                    Unit
                    <input
                      required
                      aria-label={`Column ${index + 1} unit`}
                      value={field.unit}
                      onChange={(event) => update({ unit: event.target.value })}
                    />
                  </label>
                ) : null}
                <label>
                  <input
                    type="checkbox"
                    disabled={!businessKeyTypes.includes(field.type)}
                    checked={field.businessKey}
                    onChange={(event) =>
                      update({
                        businessKey: event.target.checked,
                        nullable: event.target.checked ? false : field.nullable,
                      })
                    }
                  />
                  Unique key component
                </label>
                <label>
                  <input
                    type="checkbox"
                    disabled={field.businessKey}
                    checked={field.nullable}
                    onChange={(event) => update({ nullable: event.target.checked })}
                  />
                  Allow empty values
                </label>
                <label>
                  <input
                    type="checkbox"
                    disabled={complex}
                    checked={field.indexed}
                    onChange={(event) => update({ indexed: event.target.checked })}
                  />
                  Enable indexed filtering
                </label>
                <button
                  className="ghost-button"
                  type="button"
                  disabled={draft.fields.length === 1}
                  onClick={() =>
                    setDraft({
                      ...draft,
                      fields: draft.fields.filter((item) => item.key !== field.key),
                    })
                  }
                >
                  Remove column {index + 1}
                </button>
              </section>
            );
          })}
        </div>
        <p className="muted">
          Text: up to 120 characters. Decimal: 18 digits, up to 3 fractional digits. Integer and
          decimal values keep exact precision.
        </p>
        <button
          type="button"
          className="ghost-button"
          disabled={draft.fields.length >= 64}
          onClick={() => {
            setDraft({ ...draft, fields: [...draft.fields, newField(`field${sequence}`)] });
            setSequence(sequence + 1);
          }}
        >
          Add column
        </button>
        <label>
          <input
            type="checkbox"
            checked={draft.history}
            onChange={(event) => setDraft({ ...draft, history: event.target.checked })}
          />
          Keep row revisions
        </label>
        {draft.history ? (
          <p className="muted">Earlier versions will appear in Table history.</p>
        ) : null}
        <div className="action-row">
          <button type="submit" className="primary-button" disabled={invalidChoices}>
            Review table
          </button>
          <button type="button" className="ghost-button" onClick={onCancel}>
            Cancel
          </button>
        </div>
      </fieldset>
    </form>
  );
}

function choiceError(input?: string): string | undefined {
  const values = enumChoices(input);
  if (values.length < 1 || values.length > 64) return "Enter between 1 and 64 values.";
  if (values.some((value) => Array.from(value).length > 256))
    return "Each value can contain up to 256 characters.";
  if (new Set(values).size !== values.length) return "Enter each value only once.";
}
