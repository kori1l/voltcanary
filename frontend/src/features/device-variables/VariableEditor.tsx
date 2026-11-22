import { useEffect, useRef, useState } from "react";
import type { DataSource, Device } from "../../devices/devicesApi";
import { getTelemetryFields } from "../../telemetry/telemetryFieldsApi";
import { affine, formatExpression, parseExpression } from "./expressions";
import * as api from "./variablesApi";
import { VariableHistory } from "./VariableHistory";
import { VariableRollout } from "./VariableRollout";

interface InputDraft {
  alias: string;
  path: string;
  sample: number;
}
interface Field {
  path: string;
  key: string;
  label: string;
  unit: string;
}
export function VariableEditor({ device, sources }: { device: Device; sources: DataSource[] }) {
  const [sourceId, setSourceId] = useState(sources.length === 1 ? sources[0].id : "");
  const source = sources.find((item) => item.id === sourceId);
  const [definitions, setDefinitions] = useState<api.VariableDefinition[]>([]);
  const [detail, setDetail] = useState<api.VariableDetail | null>(null);
  const [fields, setFields] = useState<Field[]>([]);
  const [key, setKey] = useState("chamberTemperature"),
    [label, setLabel] = useState("Chamber temperature");
  const [unit, setUnit] = useState("C"),
    [mode, setMode] = useState("scale");
  const [scale, setScale] = useState(0.1),
    [offset, setOffset] = useState(-0.8);
  const [expression, setExpression] = useState("x * 0.1 - 0.8");
  const [inputs, setInputs] = useState<InputDraft[]>([{ alias: "x", path: "", sample: 235 }]);
  const [bindingState, setBindingState] = useState<api.BindingStatus | null>(null);
  const [preview, setPreview] = useState<{ signature: string; result: api.PreviewResult } | null>(
    null,
  );
  const [message, setMessage] = useState(""),
    [busy, setBusy] = useState(false),
    [refresh, setRefresh] = useState(0);
  const revision = detail?.revisions[detail.revisions.length - 1];
  const signature = JSON.stringify([
    detail?.definition.id,
    revision?.digest,
    sourceId,
    key,
    label,
    unit,
    mode,
    scale,
    offset,
    expression,
    inputs,
  ]);
  const currentSignature = useRef(signature);
  currentSignature.current = signature;
  useEffect(() => {
    const controller = new AbortController();
    void api
      .listVariables(device.type, controller.signal)
      .then(setDefinitions)
      .catch((error) => {
        if (!controller.signal.aborted) setMessage(String(error));
      });
    return () => controller.abort();
  }, [device.type, refresh]);
  useEffect(() => {
    setBindingState(null);
    setFields([]);
    setPreview(null);
    if (!source) return;
    let active = true;
    const policy = source.mapping?.telemetryPolicy as
      | {
          fields?: {
            path: string;
            key: string;
            label: string;
            type: string;
            unit: string;
            store: boolean;
          }[];
        }
      | undefined;
    if (policy)
      setFields((policy.fields ?? []).filter((field) => field.type === "number" && field.store));
    else
      void getTelemetryFields(device.id, source.externalId)
        .then((items) => {
          if (active)
            setFields(
              items
                .filter((item) => item.type === "number")
                .map((item) => ({ ...item, path: item.key })),
            );
        })
        .catch((error) => {
          if (active) setMessage(String(error));
        });
    void api
      .bindingStatus(device.id, source.id)
      .then((value) => {
        if (active) setBindingState(value);
      })
      .catch((error) => {
        if (active) setMessage(String(error));
      });
    return () => {
      active = false;
    };
  }, [device.id, source, refresh]);

  function draft(): api.VariableDraft {
    const metadata = Object.fromEntries(
      inputs.map((item) => {
        const field = fields.find((candidate) => candidate.path === item.path);
        if (!field || !item.alias || !Number.isFinite(item.sample))
          throw new Error(
            "Select a retained numeric field and a finite preview value for every input.",
          );
        return [item.alias, { unit: field.unit, minimum: null, maximum: null }];
      }),
    );
    if (Object.keys(metadata).length !== inputs.length)
      throw new Error("Input aliases must be distinct.");
    const ast =
      mode === "saved"
        ? revision!.contract.expression
        : mode === "scale"
          ? affine(scale, offset, metadata.x?.unit ?? "", unit)
          : parseExpression(expression);
    return {
      key,
      label,
      origin: "DERIVED",
      unit,
      inputs: metadata,
      expression: ast,
      minimum: null,
      maximum: null,
      expectedVersion: detail?.definition.version ?? null,
    };
  }
  function matchesSaved() {
    if (!revision) return false;
    try {
      const candidate = draft(),
        saved = revision.contract;
      return (
        candidate.key === saved.key &&
        candidate.label === saved.label &&
        candidate.unit === saved.unit &&
        JSON.stringify(candidate.inputs) === JSON.stringify(saved.inputs) &&
        JSON.stringify(candidate.expression) === JSON.stringify(saved.expression)
      );
    } catch {
      return false;
    }
  }
  async function act(work: () => Promise<void>) {
    setBusy(true);
    setMessage("");
    try {
      await work();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }
  async function choose(id: string) {
    setPreview(null);
    if (!id) {
      setDetail(null);
      setMode("scale");
      return;
    }
    const loaded = await api.variableDetail(device.type, id);
    const contract = loaded.revisions[loaded.revisions.length - 1].contract;
    setDetail(loaded);
    setKey(contract.key);
    setLabel(contract.label);
    setUnit(contract.unit);
    setMode("saved");
    const binding = bindingState?.bindings.find((item) => item.variableId === id);
    setInputs(
      Object.keys(contract.inputs).map((alias) => ({
        alias,
        path: binding?.inputPaths[alias] ?? "",
        sample: 235,
      })),
    );
  }
  const bound = bindingState?.bindings.find((item) => item.variableId === detail?.definition.id);
  const published = revision?.state === "PUBLISHED";
  const freshPreview = preview?.signature === signature && preview.result.status === "OK";
  const unchanged = matchesSaved();
  return (
    <div className="form-stack">
      <section className="panel">
        <h2>Variables for {device.type}</h2>
        <p>
          Definitions are shared by this account’s device type. Choose a source explicitly before
          checking or activating a calculation.
        </p>
        {message ? <p role="status">{message}</p> : null}
        <fieldset disabled={busy}>
          <div className="form-grid">
            <label>
              Source
              <select
                aria-label="Variable source"
                value={sourceId}
                onChange={(event) => setSourceId(event.target.value)}
              >
                <option value="">Choose source</option>
                {sources.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.externalId}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Variable
              <select
                aria-label="Variable definition"
                value={detail?.definition.id ?? ""}
                onChange={(event) => void act(() => choose(event.target.value))}
              >
                <option value="">New variable</option>
                {definitions.map((item) => (
                  <option key={item.id} value={item.id}>
                    {item.label}
                    {item.archived ? " · archived" : ""}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Key
              <input
                value={key}
                disabled={!!detail}
                onChange={(event) => setKey(event.target.value)}
              />
            </label>
            <label>
              Name
              <input
                value={label}
                maxLength={80}
                onChange={(event) => setLabel(event.target.value)}
              />
            </label>
            <label>
              Output unit
              <select
                value={unit}
                disabled={!!detail}
                onChange={(event) => setUnit(event.target.value)}
              >
                {[
                  "C",
                  "F",
                  "K",
                  "V",
                  "A",
                  "W",
                  "Wh",
                  "kWh",
                  "%",
                  "ratio",
                  "s",
                  "h",
                  "km/h",
                  "mm/s",
                  "dBm",
                  "",
                ].map((value) => (
                  <option key={value} value={value}>
                    {value || "No unit"}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Calculation
              <select
                value={mode}
                onChange={(event) => {
                  setMode(event.target.value);
                  if (event.target.value === "scale")
                    setInputs((prior) => [{ ...prior[0], alias: "x" }]);
                }}
              >
                <option value="scale">Scale, convert units and apply offset</option>
                <option value="expression">Arithmetic expression</option>
                {revision ? <option value="saved">Saved expression</option> : null}
              </select>
            </label>
          </div>
          {mode === "scale" ? (
            <div className="form-grid">
              <label>
                Scale
                <input
                  type="number"
                  step="any"
                  value={scale}
                  onChange={(event) => setScale(Number(event.target.value))}
                />
              </label>
              <label>
                Offset in output unit
                <input
                  type="number"
                  step="any"
                  value={offset}
                  onChange={(event) => setOffset(Number(event.target.value))}
                />
              </label>
            </div>
          ) : mode === "expression" ? (
            <label>
              Expression
              <input
                value={expression}
                maxLength={2048}
                onChange={(event) => setExpression(event.target.value)}
              />
              <small>Use input aliases, numbers, + − * / and parentheses.</small>
            </label>
          ) : (
            <p>
              <code>{revision ? formatExpression(revision.contract.expression) : ""}</code>
            </p>
          )}
          {inputs.map((item, index) => (
            <div className="form-grid-three" key={index}>
              <label>
                Input alias
                <input
                  value={item.alias}
                  disabled={mode !== "expression"}
                  maxLength={32}
                  onChange={(event) =>
                    setInputs((prior) =>
                      prior.map((entry, i) =>
                        i === index ? { ...entry, alias: event.target.value } : entry,
                      ),
                    )
                  }
                />
              </label>
              <label>
                Retained numeric field
                <select
                  value={item.path}
                  onChange={(event) =>
                    setInputs((prior) =>
                      prior.map((entry, i) =>
                        i === index ? { ...entry, path: event.target.value } : entry,
                      ),
                    )
                  }
                >
                  <option value="">Choose field</option>
                  {fields.map((field) => (
                    <option key={field.path} value={field.path}>
                      {field.label} · {field.unit || "no unit"}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                Preview input
                <input
                  type="number"
                  step="any"
                  value={item.sample}
                  onChange={(event) =>
                    setInputs((prior) =>
                      prior.map((entry, i) =>
                        i === index ? { ...entry, sample: Number(event.target.value) } : entry,
                      ),
                    )
                  }
                />
              </label>
            </div>
          ))}
          {mode === "expression" ? (
            <div className="action-row">
              <button
                className="ghost-button"
                type="button"
                disabled={inputs.length >= 8}
                onClick={() =>
                  setInputs((prior) => [
                    ...prior,
                    { alias: `x${prior.length + 1}`, path: "", sample: 0 },
                  ])
                }
              >
                Add input
              </button>
              <button
                className="ghost-button"
                type="button"
                disabled={inputs.length <= 1}
                onClick={() => setInputs((prior) => prior.slice(0, -1))}
              >
                Remove last input
              </button>
            </div>
          ) : null}
          <p>
            Inputs are values after protocol decoding. An existing Modbus scale is already applied.
            Retain required numeric fields in source settings first.
          </p>
          <div className="action-row">
            <button
              className="ghost-button"
              type="button"
              disabled={!source || detail?.definition.archived}
              onClick={() =>
                void act(async () => {
                  const saved = await api.saveVariable(device.type, draft(), detail?.definition.id);
                  setDetail(saved);
                  setMode("saved");
                  setPreview(null);
                  setRefresh((value) => value + 1);
                  setMessage("Draft saved. Check it with sample data before publishing.");
                })
              }
            >
              Save draft
            </button>
            <button
              className="ghost-button"
              type="button"
              disabled={!revision || !source || !unchanged || detail?.definition.archived}
              onClick={() =>
                void act(async () => {
                  const captured = signature;
                  const result = await api.previewVariable(
                    device.id,
                    sourceId,
                    revision!.contract,
                    Object.fromEntries(inputs.map((item) => [item.alias, item.sample])),
                  );
                  if (currentSignature.current === captured)
                    setPreview({ signature: captured, result });
                })
              }
            >
              Preview calculation
            </button>
            <button
              className="ghost-button"
              type="button"
              disabled={!freshPreview || !unchanged || published || detail?.definition.archived}
              onClick={() =>
                void act(async () => {
                  setDetail(
                    await api.publishVariable(
                      device.type,
                      detail!.definition.id,
                      detail!.definition.version,
                      revision!.revision,
                    ),
                  );
                  setPreview(null);
                  setRefresh((value) => value + 1);
                  setMessage("Revision published. Activate it on the selected source when ready.");
                })
              }
            >
              Publish revision
            </button>
            <button
              className="ghost-button"
              type="button"
              disabled={!published || !source || !unchanged || detail?.definition.archived}
              onClick={() =>
                void act(async () => {
                  await api.bindVariable(
                    device.id,
                    sourceId,
                    detail!.definition.id,
                    revision!.revision,
                    bound?.version ?? 0,
                    Object.fromEntries(inputs.map((item) => [item.alias, item.path])),
                  );
                  setRefresh((value) => value + 1);
                  setMessage(
                    "Activation requested. Waiting for processing configuration and a new reading.",
                  );
                })
              }
            >
              Activate on this source
            </button>
          </div>
          {preview?.signature === signature ? (
            <div role="status">
              <strong>
                {preview.result.status === "OK"
                  ? `${preview.result.value} ${preview.result.unit}`
                  : preview.result.status}
              </strong>
              <p>{preview.result.steps.map((step) => `${step.value} ${step.unit}`).join(" → ")}</p>
            </div>
          ) : preview ? (
            <p>Preview is stale. Check the current inputs again.</p>
          ) : null}
          {detail ? (
            <div className="action-row">
              <button
                className="ghost-button"
                type="button"
                disabled={!published || !source || detail.definition.archived}
                onClick={() =>
                  void act(async () => {
                    await api.setVariableDefault(
                      device.type,
                      detail.definition.id,
                      detail.definition.version,
                      revision!.revision,
                      source!.sourceType,
                      Object.fromEntries(inputs.map((item) => [item.alias, item.path])),
                    );
                    const outcomes = await api.reconcileVariableDefault(
                      device.type,
                      detail.definition.id,
                    );
                    setDetail(await api.variableDetail(device.type, detail.definition.id));
                    setRefresh((value) => value + 1);
                    setMessage(
                      `Type default saved. ${outcomes.map((item) => `${item.deviceId}: ${item.status}${item.diagnostic ? " — " + item.diagnostic : ""}`).join("; ") || "Existing bindings remain pinned; future eligible sources inherit the default."}`,
                    );
                  })
                }
              >
                Use for new unbound sources of this type
              </button>
              <button
                className="ghost-button"
                type="button"
                disabled={!bound?.enabled}
                onClick={() =>
                  void act(async () => {
                    await api.bindVariable(
                      device.id,
                      sourceId,
                      detail.definition.id,
                      bound!.revision,
                      bound!.version,
                      bound!.inputPaths,
                      false,
                    );
                    setRefresh((value) => value + 1);
                  })
                }
              >
                Disable source calculation
              </button>
              <button
                className="ghost-button"
                type="button"
                disabled={detail.definition.archived}
                onClick={() =>
                  void act(async () => {
                    await api.archiveVariable(
                      device.type,
                      detail.definition.id,
                      detail.definition.version,
                    );
                    setDetail(await api.variableDetail(device.type, detail.definition.id));
                    setRefresh((value) => value + 1);
                  })
                }
              >
                Archive variable, keep history
              </button>
            </div>
          ) : null}
        </fieldset>
      </section>
      {published && detail && !detail.definition.archived && source ? (
        <VariableRollout
          key={`${detail.definition.id}:${revision!.revision}`}
          type={device.type}
          variableId={detail.definition.id}
          revision={revision!.revision}
          inputPaths={Object.fromEntries(inputs.map((item) => [item.alias, item.path]))}
        />
      ) : null}
      <VariableHistory
        deviceId={device.id}
        sourceId={sourceId}
        desiredGeneration={bindingState?.desiredGeneration ?? 0}
        bindings={bindingState?.bindings ?? []}
        refreshKey={refresh}
      />
    </div>
  );
}
