import { useEffect, useRef, useState, type FormEvent } from "react";
import { compileConfigurableRule, type RuleCompilation } from "./configurableRuleApi";
import type { Language } from "./nativeDataApi";
import { ConfigurableRuleWindowPreview, type PreviewNode } from "./ConfigurableRuleWindowPreview";
import { ConfigurableRuleActivation } from "./ConfigurableRuleActivation";
import { RuleDependencyComparison } from "./RuleDependencyComparison";
import { RuleNodeSettings, type NodeSettingsSchema } from "./RuleNodeSettings";
import { me } from "../../auth/authApi";
import { getCurrentDeviceAccount } from "../../devices/devicesApi";
import { readRuleActivation, type RuleActivationRecovery } from "./ruleActivationRecovery";
import { emptyRuleCoverage, RuleSourceCoverage } from "./RuleSourceCoverage";

type JsonObject = Record<string, unknown>;
type DraftNode = {
  key: number;
  id: string;
  method: string;
  inputs: Record<string, number>;
  config: string;
};
type PublishedNode = {
  properties?: {
    method?: { const?: string };
    inputs?: { properties?: JsonObject; required?: string[] };
    config?: NodeSettingsSchema;
  };
};

function isObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function readObject(value: string, label: string): JsonObject {
  let parsed: unknown;
  try {
    parsed = JSON.parse(value, (_key, item: unknown) => {
      if (
        typeof item === "number" &&
        (!Number.isFinite(item) || (Number.isInteger(item) && !Number.isSafeInteger(item)))
      ) {
        throw new Error("A number exceeds the browser's supported range.");
      }
      return item;
    });
  } catch (failure) {
    throw new Error(`${label}: ${(failure as Error).message}`);
  }
  if (!isObject(parsed)) throw new Error(`${label} must be a JSON object.`);
  return parsed;
}

const emptySpec = JSON.stringify(
  {
    ruleId: "",
    revision: 1,
    name: "",
    executionGrantId: "",
    coverage: {},
    decimalPolicy: {},
    timing: {},
  },
  null,
  2,
);

export function ConfigurableRuleWorkbench({
  language,
  activationOpen,
  onActivationChange,
}: {
  language: Language | null;
  activationOpen: boolean;
  onActivationChange: (active: boolean) => void;
}) {
  const [spec, setSpec] = useState(emptySpec);
  const [coverage, setCoverage] = useState(emptyRuleCoverage);
  const [readSet, setReadSet] = useState<string | null>(null);
  const [nodes, setNodes] = useState<DraftNode[]>([]);
  const [terminal, setTerminal] = useState<number | null>(null);
  const [prepared, setPrepared] = useState<{
    compilation: RuleCompilation;
    account: string;
    ruleId: string;
    grantId: string;
    windowNodes: PreviewNode[];
    sessionNodes: PreviewNode[];
    circles: string[];
  } | null>(null);
  const [comparison, setComparison] = useState<RuleCompilation | null>(null);
  const lastPrepared = useRef<{
    account: string;
    ruleId: string;
    compilation: RuleCompilation;
  } | null>(null);
  const compilation = prepared?.compilation;
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [actorId, setActorId] = useState<string | null>(null);
  const [identityError, setIdentityError] = useState<string | null>(null);
  const [recovery, setRecovery] = useState<RuleActivationRecovery | null>(null);
  const sequence = useRef(0);
  const ruleSchema = language?.registry.$defs.Rule as
    | {
        properties?: {
          nodes?: {
            items?: {
              oneOf?: PublishedNode[];
            };
          };
        };
      }
    | undefined;
  const methods = new Map(
    (ruleSchema?.properties?.nodes?.items?.oneOf ?? []).flatMap((node) => {
      const method = node.properties?.method?.const;
      const inputs = node.properties?.inputs;
      return method && inputs?.properties
        ? [
            [
              method,
              {
                ports: Object.keys(inputs.properties),
                required: inputs.required ?? [],
                config: node.properties?.config,
              },
            ] as const,
          ]
        : [];
    }),
  );
  const selectedTerminal = nodes.find((node) => node.key === terminal);
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);
  useEffect(() => {
    let active = true;
    void me()
      .then((actor) => {
        if (!active) return;
        const saved = readRuleActivation(actor.id);
        setActorId(actor.id);
        setRecovery(saved);
        if (saved) onActivationChange(true);
      })
      .catch((failure) => {
        if (active) setIdentityError((failure as Error).message);
      });
    return () => {
      active = false;
    };
  }, [onActivationChange]);

  function edit(update: () => void) {
    update();
    setPrepared(null);
    setError(null);
  }

  function ownerSet(account: string, source: JsonObject) {
    return {
      account,
      binding: source.bindingId,
      schemaRevision: source.schemaRevision,
      from: coverage.from,
      boundary: coverage.boundary,
      proofId: coverage.proofId,
      ...(coverage.circles.length ? { circles: coverage.circles } : {}),
    };
  }

  async function prepare(event: FormEvent) {
    event.preventDefault();
    if (busy || activationOpen) return;
    setPrepared(null);
    setError(null);
    const controller = new AbortController();
    request.current = controller;
    setBusy(true);
    try {
      const metadata = readObject(spec, "Rule settings");
      if ("nodes" in metadata || "terminal" in metadata)
        throw new Error("Choose graph nodes and the output node in the editor.");
      const ids = nodes.map((node) => node.id.trim());
      if (ids.some((id) => !id) || new Set(ids).size !== ids.length)
        throw new Error("Each node needs a distinct name.");
      if (!selectedTerminal) throw new Error("Choose a rule output node.");
      const graph = nodes.map((node) => {
        const method = methods.get(node.method);
        if (!method) throw new Error(`Choose a published method for ${node.id}.`);
        const inputs = Object.fromEntries(
          method.ports.flatMap((port) => {
            const from = nodes.find(
              (candidate) => candidate.key === node.inputs[port] && candidate.key !== node.key,
            );
            if (!from && method.required.includes(port))
              throw new Error(`Choose an input for ${node.id} · ${port}.`);
            return from ? [[port, from.id.trim()]] : [];
          }),
        );
        return {
          id: node.id.trim(),
          method: node.method,
          inputs,
          config: readObject(node.config, `Settings for ${node.id}`),
        };
      });
      let owners: JsonObject;
      if (readSet !== null) {
        owners = readObject(readSet, "Owner read set");
      } else {
        const sources = graph.filter((node) => node.method === "read_stream");
        if (sources.length !== 1)
          throw new Error("Choose one Read stream node for source coverage.");
        const source = sources[0].config;
        if (
          typeof source.bindingId !== "string" ||
          typeof source.schemaRevision !== "number" ||
          source.schemaRevision < 1
        ) {
          throw new Error("Set the source binding ID and schema revision on the Read stream node.");
        }
        if (!coverage.proofId || !coverage.from || !coverage.boundary)
          throw new Error("Complete the source coverage settings.");
        const account = await getCurrentDeviceAccount(controller.signal);
        if (controller.signal.aborted) return;
        owners = ownerSet(account.id, source);
      }
      const result = await compileConfigurableRule(
        {
          language: "VCL/1",
          kind: "RULE",
          spec: { ...metadata, nodes: graph, terminal: selectedTerminal.id.trim() },
        },
        owners,
        controller.signal,
      );
      if (controller.signal.aborted) return;
      const account = String(owners.account).toLowerCase();
      const ruleId = String(metadata.ruleId).toLowerCase();
      const previous = lastPrepared.current;
      setComparison(
        previous?.account === account && previous.ruleId === ruleId ? previous.compilation : null,
      );
      lastPrepared.current = { account, ruleId, compilation: result };
      const methodsByNode = new Map(graph.map(({ id, method }) => [id, method]));
      setPrepared({
        compilation: result,
        account,
        ruleId,
        grantId: String(metadata.executionGrantId).toLowerCase(),
        windowNodes: graph
          .filter((node) => ["window_aggregate", "interval_union_window"].includes(node.method))
          .map(({ id }) => ({ id })),
        sessionNodes: graph
          .filter((node) => node.method === "absent_within")
          .map(({ id, inputs }) => ({
            id,
            zone: methodsByNode.get(String(inputs.sessions)) === "zone_session",
          })),
        circles: Array.isArray(owners.circles)
          ? owners.circles.flatMap((circle: unknown) =>
              isObject(circle) && typeof circle.id === "string" ? [circle.id] : [],
            )
          : [],
      });
    } catch (failure) {
      if (!controller.signal.aborted) setError((failure as Error).message);
    } finally {
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return (
    <section className="panel form-stack" aria-label="Configurable rules">
      <h2>Configurable rules</h2>
      <form className="form-stack" onSubmit={(event) => void prepare(event)}>
        <fieldset className="form-stack" disabled={busy || activationOpen}>
          <legend>Rule graph</legend>
          {!methods.size ? (
            <p role="status">Rule methods are unavailable from the current VCL registry.</p>
          ) : null}
          <label>
            Rule settings (JSON)
            <textarea
              rows={6}
              required
              value={spec}
              onChange={(event) => edit(() => setSpec(event.target.value))}
            />
          </label>
          {nodes.map((node, index) => {
            const method = methods.get(node.method);
            function update(change: Partial<DraftNode>) {
              edit(() =>
                setNodes((items) =>
                  items.map((item) => (item.key === node.key ? { ...item, ...change } : item)),
                ),
              );
            }
            return (
              <fieldset className="native-field" key={node.key}>
                <legend>Node {node.id.trim() || index + 1}</legend>
                <label>
                  Node name
                  <input
                    required
                    value={node.id}
                    onChange={(event) => update({ id: event.target.value })}
                  />
                </label>
                <label>
                  Method
                  <select
                    required
                    value={node.method}
                    onChange={(event) => {
                      const selected = event.target.value;
                      const ports = methods.get(selected)?.ports ?? [];
                      update({
                        method: selected,
                        inputs: Object.fromEntries(
                          Object.entries(node.inputs).filter(([port]) => ports.includes(port)),
                        ),
                      });
                    }}
                  >
                    <option value="">Select method</option>
                    {Array.from(methods.keys(), (name) => (
                      <option key={name} value={name}>
                        {name.replace(/_/g, " ")}
                      </option>
                    ))}
                  </select>
                </label>
                {method?.ports.map((port) => (
                  <label key={port}>
                    Input {port.replace(/_/g, " ")}
                    <select
                      required={method.required.includes(port)}
                      value={
                        nodes.some((candidate) => candidate.key === node.inputs[port])
                          ? node.inputs[port]
                          : ""
                      }
                      onChange={(event) => {
                        const inputs = { ...node.inputs };
                        if (event.target.value === "") delete inputs[port];
                        else inputs[port] = Number(event.target.value);
                        update({ inputs });
                      }}
                    >
                      <option value="">Select input node</option>
                      {nodes
                        .filter((candidate) => candidate.key !== node.key)
                        .map((candidate) => (
                          <option key={candidate.key} value={candidate.key}>
                            {candidate.id.trim() || "Unnamed node"}
                          </option>
                        ))}
                    </select>
                  </label>
                ))}
                <RuleNodeSettings
                  schema={method?.config}
                  json={node.config}
                  onChange={(config) => update({ config })}
                />
                <button
                  className="ghost-button"
                  type="button"
                  onClick={() =>
                    edit(() => {
                      setNodes((items) => items.filter((item) => item.key !== node.key));
                      if (terminal === node.key) setTerminal(null);
                    })
                  }
                >
                  Remove node
                </button>
              </fieldset>
            );
          })}
          <button
            className="ghost-button"
            type="button"
            disabled={!methods.size}
            onClick={() => {
              const key = sequence.current++;
              edit(() =>
                setNodes((items) => [
                  ...items,
                  { key, id: "", method: "", inputs: {}, config: "{}" },
                ]),
              );
            }}
          >
            Add node
          </button>
          {nodes.length ? (
            <label>
              Rule output
              <select
                required
                value={terminal ?? ""}
                onChange={(event) => {
                  const value = event.target.value;
                  edit(() => setTerminal(value === "" ? null : Number(value)));
                }}
              >
                <option value="">Select output node</option>
                {nodes.map((node) => (
                  <option key={node.key} value={node.key}>
                    {node.id.trim() || "Unnamed node"}
                  </option>
                ))}
              </select>
            </label>
          ) : null}
          {readSet === null ? (
            <RuleSourceCoverage
              value={coverage}
              onChange={(next) => edit(() => setCoverage(next))}
            />
          ) : (
            <label>
              Owner read set (JSON)
              <textarea
                rows={5}
                required
                value={readSet}
                onChange={(event) => edit(() => setReadSet(event.target.value))}
              />
            </label>
          )}
          <button
            className="ghost-button"
            type="button"
            onClick={() =>
              edit(() =>
                setReadSet(readSet === null ? JSON.stringify(ownerSet("", {}), null, 2) : null),
              )
            }
          >
            {readSet === null
              ? "Enter an advanced read set"
              : "Discard JSON edits and use coverage fields"}
          </button>
          <button type="submit" className="primary-button" disabled={!nodes.length}>
            Prepare rule
          </button>
        </fieldset>
      </form>
      {busy ? <p role="status">Validating rule against current sources and permissions…</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {compilation ? (
        <section className="native-field" aria-label="Prepared rule">
          <h3>Prepared rule</h3>
          <p>
            Source window: {compilation.checkpoint.source.from} to{" "}
            {compilation.checkpoint.source.boundary}
          </p>
          <details>
            <summary>Bundle and dependency digests</summary>
            <dl>
              <dt>Bundle ID</dt>
              <dd>{compilation.bundleId}</dd>
              <dt>Bundle digest</dt>
              <dd>{compilation.bundleDigest}</dd>
              <dt>Start checkpoint</dt>
              <dd>{compilation.checkpoint.digest}</dd>
              <dt>Source checkpoint</dt>
              <dd>{compilation.checkpoint.source.digest}</dd>
              {Object.entries(compilation.checkpoint.references).map(([node, pin]) => (
                <div key={node}>
                  <dt>Reference {node}</dt>
                  <dd>{pin.manifestDigest}</dd>
                </div>
              ))}
            </dl>
          </details>
          {comparison ? (
            <RuleDependencyComparison
              previous={comparison}
              current={compilation}
              title="Changes since the previous preparation"
            />
          ) : null}
        </section>
      ) : null}
      {prepared?.windowNodes.length ? (
        <ConfigurableRuleWindowPreview
          key="window"
          {...prepared}
          kind="window"
          nodes={prepared.windowNodes}
        />
      ) : null}
      {prepared?.sessionNodes.length ? (
        <ConfigurableRuleWindowPreview
          key="session"
          {...prepared}
          kind="session"
          nodes={prepared.sessionNodes}
        />
      ) : null}
      {identityError ? (
        <p role="alert">Activation identity could not be loaded. {identityError}</p>
      ) : null}
      {actorId && recovery ? (
        <ConfigurableRuleActivation
          {...recovery.context}
          actorId={actorId}
          recovery={recovery}
          onReviewing={onActivationChange}
          onClose={() => setRecovery(null)}
        />
      ) : actorId && prepared ? (
        <ConfigurableRuleActivation
          {...prepared}
          actorId={actorId}
          onReviewing={onActivationChange}
        />
      ) : null}
    </section>
  );
}
