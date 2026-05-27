import { FormEvent, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import {
  AgentPlan,
  AgentProviderStatus,
  createAgentPlan,
  ExecutePlanResponse,
  executeAgentPlan,
  getAgentProviderStatus,
  listAgentTools,
  listModelProviders,
  ModelConfig,
  ModelProvider,
} from "../aiAgent/aiAgentApi";

const EXAMPLES = [
  "Create a fridge simulator with HTTP telemetry and normal power cycle.",
  "Create a smart plug device and attach a simulator data source.",
  "Validate this simulator YAML before I save it.",
];

export function AiAgentPage() {
  const [message, setMessage] = useState(EXAMPLES[0]);
  const [contextJson, setContextJson] = useState("{\n  \"profile\": \"FRIDGE\",\n  \"sourceId\": \"sim-fridge-001\"\n}");
  const [plan, setPlan] = useState<AgentPlan | null>(null);
  const [execution, setExecution] = useState<ExecutePlanResponse | null>(null);
  const [tools, setTools] = useState<string[]>([]);
  const [providerStatus, setProviderStatus] = useState<AgentProviderStatus | null>(null);
  const [providers, setProviders] = useState<ModelProvider[]>([]);
  const [models, setModels] = useState<ModelConfig[]>([]);
  const [selectedProviderId, setSelectedProviderId] = useState("");
  const [selectedModelConfigId, setSelectedModelConfigId] = useState("");
  const [isPlanning, setIsPlanning] = useState(false);
  const [isExecuting, setIsExecuting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    listAgentTools().then(setTools).catch(() => setTools([]));
    getAgentProviderStatus().then(setProviderStatus).catch(() => setProviderStatus(null));
    listModelProviders()
      .then((items) => {
        setProviders(items);
        const defaultProvider = items.find((provider) => provider.isDefault) ?? items[0];
        if (defaultProvider) {
          setSelectedProviderId(defaultProvider.id);
          setModels(defaultProvider.models);
          setSelectedModelConfigId((defaultProvider.models.find((model) => model.isDefault) ?? defaultProvider.models[0])?.id ?? "");
        }
      })
      .catch(() => setProviders([]));
  }, []);

  useEffect(() => {
    const provider = providers.find((item) => item.id === selectedProviderId);
    setModels(provider?.models ?? []);
    setSelectedModelConfigId((provider?.models.find((model) => model.isDefault) ?? provider?.models[0])?.id ?? "");
  }, [providers, selectedProviderId]);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setIsPlanning(true);
    setError(null);
    setExecution(null);
    try {
      const context = contextJson.trim() ? JSON.parse(contextJson) as Record<string, unknown> : {};
      setPlan(await createAgentPlan(message, context, selectedProviderId || undefined, selectedModelConfigId || undefined));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create agent plan");
    } finally {
      setIsPlanning(false);
    }
  }

  async function execute(confirmed: boolean) {
    if (!plan) return;
    setIsExecuting(true);
    setError(null);
    try {
      setExecution(await executeAgentPlan(plan.planId, confirmed));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to execute plan");
    } finally {
      setIsExecuting(false);
    }
  }

  return (
    <>
      <header className="page-header page-header-row">
        <div>
          <h1>AI Agent</h1>
          <p>Ask the agent to plan device, data source, and simulator setup. Mutating actions require confirmation.</p>
        </div>
        {providerStatus ? (
          <div className="agent-provider-status">
            <span>{providerStatus.provider.toUpperCase()}</span>
            <strong>{providerStatus.model}</strong>
            {!providerStatus.enabled ? <em>LLM fallback</em> : null}
          </div>
        ) : null}
      </header>

      {error ? <div className="error-message">{error}</div> : null}

      <section className="agent-layout">
        <form className="panel agent-compose" onSubmit={submit}>
          <div>
            <h2>Ask a question</h2>
            <p className="muted-text">The agent plans actions first and explains what it will do before execution.</p>
          </div>
          <label>
            Request
            <textarea value={message} onChange={(event) => setMessage(event.target.value)} rows={5} />
          </label>
          <div className="agent-model-selector">
            <label>
              Provider
              <select value={selectedProviderId} onChange={(event) => setSelectedProviderId(event.target.value)}>
                <option value="">Rules fallback</option>
                {providers.map((provider) => (
                  <option value={provider.id} key={provider.id}>
                    {provider.name} {provider.isDefault ? "(default)" : ""}
                  </option>
                ))}
              </select>
            </label>
            <label>
              Model
              <select value={selectedModelConfigId} onChange={(event) => setSelectedModelConfigId(event.target.value)} disabled={!selectedProviderId}>
                <option value="">Default model</option>
                {models.map((model) => (
                  <option value={model.id} key={model.id}>
                    {model.displayName} - {model.modelId}
                  </option>
                ))}
              </select>
            </label>
            <Link className="ghost-button button-link" to="/ai-agent/settings">
              Model settings
            </Link>
          </div>
          <label>
            Context JSON
            <textarea value={contextJson} onChange={(event) => setContextJson(event.target.value)} rows={8} spellCheck={false} />
          </label>
          <button className="primary-button" type="submit" disabled={isPlanning || !message.trim()}>
            {isPlanning ? "Planning..." : "Create plan"}
          </button>
          <div className="agent-examples">
            <strong>Examples</strong>
            {EXAMPLES.map((example) => (
              <button type="button" key={example} onClick={() => setMessage(example)}>
                {example}
              </button>
            ))}
          </div>
        </form>

        <div className="agent-result-column">
          <section className="panel agent-tools-panel">
            <h2>Available tools</h2>
            <div className="agent-tool-list">
              {tools.map((tool) => <span key={tool}>{formatEnum(tool)}</span>)}
            </div>
          </section>

          {plan ? (
            <section className="panel agent-plan-panel">
              <div className="section-header">
                <div>
                  <h2>{plan.summary}</h2>
                  <p>{plan.explanation}</p>
                </div>
                <span className={plan.confirmationRequired ? "agent-confirmation-badge warning" : "agent-confirmation-badge"}>
                  {plan.confirmationRequired ? "Confirmation required" : "Read-only"}
                </span>
              </div>
              {plan.providerType || plan.modelId ? (
                <div className="agent-plan-model-meta">
                  <span>{plan.providerName ?? plan.providerType ?? "rules"}</span>
                  <strong>{plan.modelId ?? "rule-based planner"}</strong>
                  {plan.fallbackUsed ? <em>Fallback used</em> : null}
                </div>
              ) : null}

              <div className="agent-safety-notes">
                {plan.safetyNotes.map((note) => <span key={note}>{note}</span>)}
              </div>

              <div className="agent-action-list">
                {plan.actions.map((action, index) => (
                  <article className="agent-action-card" key={action.id}>
                    <div>
                      <span>Step {index + 1} · {formatEnum(action.tool)}</span>
                      <strong>{action.title}</strong>
                      <p>{action.description}</p>
                    </div>
                    {action.requiresConfirmation ? <em>Requires confirmation</em> : <em>Read-only</em>}
                    <details>
                      <summary>Parameters</summary>
                      <pre>{JSON.stringify(action.parameters, null, 2)}</pre>
                    </details>
                  </article>
                ))}
              </div>

              <div className="action-row">
                <button className="ghost-button" type="button" onClick={() => void execute(false)} disabled={isExecuting}>
                  Try without confirmation
                </button>
                <button className="primary-button" type="button" onClick={() => void execute(true)} disabled={isExecuting}>
                  {isExecuting ? "Executing..." : "Confirm and execute"}
                </button>
              </div>
            </section>
          ) : (
            <section className="panel empty-state">
              <strong>No plan yet</strong>
              <span>Ask the agent what you want to configure.</span>
            </section>
          )}

          {execution ? (
            <section className="panel agent-execution-panel">
              <h2>Execution result: {formatEnum(execution.status)}</h2>
              <div className="agent-action-list">
                {execution.actions.map((action) => (
                  <article className={`agent-action-card execution-${action.status.toLowerCase()}`} key={action.actionId}>
                    <div>
                      <span>{formatEnum(action.tool)}</span>
                      <strong>{formatEnum(action.status)}</strong>
                      {action.error ? <p>{action.error}</p> : null}
                    </div>
                    <details>
                      <summary>Result</summary>
                      <pre>{JSON.stringify(action.result, null, 2)}</pre>
                    </details>
                  </article>
                ))}
              </div>
            </section>
          ) : null}
        </div>
      </section>
    </>
  );
}

function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split("_")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
