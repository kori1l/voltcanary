import { FormEvent, useMemo, useState } from "react";
import { Link, useLocation } from "react-router-dom";
import {
  AgentPlan,
  createAgentPlan,
  ExecutePlanResponse,
  executeAgentPlan,
} from "./aiAgentApi";

const DEFAULT_PROMPTS = [
  "Explain the current issue and suggest the next step.",
  "Create a demo device with realistic telemetry.",
  "Suggest an alert rule for this device.",
];

export function AssistantDrawer() {
  const location = useLocation();
  const [isOpen, setIsOpen] = useState(false);
  const [message, setMessage] = useState(DEFAULT_PROMPTS[0]);
  const [plan, setPlan] = useState<AgentPlan | null>(null);
  const [execution, setExecution] = useState<ExecutePlanResponse | null>(null);
  const [isPlanning, setIsPlanning] = useState(false);
  const [isExecuting, setIsExecuting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const context = useMemo(() => buildAssistantContext(location.pathname, location.search), [location.pathname, location.search]);

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!message.trim()) {
      return;
    }
    setIsPlanning(true);
    setError(null);
    setExecution(null);
    try {
      setPlan(await createAgentPlan(message.trim(), context));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to create assistant plan");
    } finally {
      setIsPlanning(false);
    }
  }

  async function execute(confirmed: boolean) {
    if (!plan) {
      return;
    }
    setIsExecuting(true);
    setError(null);
    try {
      setExecution(await executeAgentPlan(plan.planId, confirmed));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to execute assistant plan");
    } finally {
      setIsExecuting(false);
    }
  }

  return (
    <>
      <button className="assistant-button" type="button" onClick={() => setIsOpen(true)}>
        AI
      </button>
      {isOpen ? (
        <div className="assistant-drawer-backdrop" role="presentation" onMouseDown={() => setIsOpen(false)}>
          <aside className="assistant-drawer" role="dialog" aria-modal="true" aria-label="AI assistant" onMouseDown={(event) => event.stopPropagation()}>
            <header className="assistant-drawer-header">
              <div>
                <h2>Assistant</h2>
                <span>{context.pageLabel}</span>
              </div>
              <button className="ghost-button" type="button" onClick={() => setIsOpen(false)}>
                Close
              </button>
            </header>

            <form className="assistant-compose" onSubmit={submit}>
              <textarea value={message} onChange={(event) => setMessage(event.target.value)} rows={4} />
              <button className="primary-button" type="submit" disabled={isPlanning || !message.trim()}>
                {isPlanning ? "Planning..." : "Create plan"}
              </button>
            </form>

            <div className="assistant-prompt-list">
              {DEFAULT_PROMPTS.map((prompt) => (
                <button type="button" key={prompt} onClick={() => setMessage(prompt)}>
                  {prompt}
                </button>
              ))}
            </div>

            {error ? <div className="error-message">{error}</div> : null}

            {plan ? (
              <section className="assistant-plan">
                <div className="section-header">
                  <div>
                    <h3>{plan.summary}</h3>
                    <p>{plan.explanation}</p>
                  </div>
                  <span className={plan.confirmationRequired ? "agent-confirmation-badge warning" : "agent-confirmation-badge"}>
                    {plan.confirmationRequired ? "Confirm" : "Read-only"}
                  </span>
                </div>
                {plan.safetyNotes.length > 0 ? (
                  <div className="agent-safety-notes">
                    {plan.safetyNotes.map((note) => <span key={note}>{note}</span>)}
                  </div>
                ) : null}
                <div className="agent-action-list">
                  {plan.actions.map((action, index) => (
                    <article className="agent-action-card" key={action.id}>
                      <span>Step {index + 1}</span>
                      <strong>{action.title}</strong>
                      <p>{action.description}</p>
                      {action.requiresConfirmation ? <em>Requires confirmation</em> : <em>Read-only</em>}
                    </article>
                  ))}
                </div>
                <div className="action-row action-row-start">
                  <button className="ghost-button" type="button" onClick={() => void execute(false)} disabled={isExecuting}>
                    Run read-only
                  </button>
                  <button className="primary-button" type="button" onClick={() => void execute(true)} disabled={isExecuting}>
                    {isExecuting ? "Executing..." : "Confirm"}
                  </button>
                </div>
              </section>
            ) : null}

            {execution ? (
              <section className="assistant-execution">
                <h3>Result: {formatEnum(execution.status)}</h3>
                <div className="agent-action-list">
                  {execution.actions.map((action) => {
                    const route = typeof action.result?.route === "string" ? action.result.route : null;
                    return (
                      <article className={`agent-action-card execution-${action.status.toLowerCase()}`} key={action.actionId}>
                        <span>{formatEnum(action.tool)}</span>
                        <strong>{formatEnum(action.status)}</strong>
                        {action.error ? <p>{action.error}</p> : null}
                        {route ? (
                          <Link className="ghost-button button-link" to={route} onClick={() => setIsOpen(false)}>
                            Open
                          </Link>
                        ) : null}
                      </article>
                    );
                  })}
                </div>
              </section>
            ) : null}

            <details className="assistant-advanced">
              <summary>Advanced</summary>
              <pre>{JSON.stringify(context, null, 2)}</pre>
            </details>
          </aside>
        </div>
      ) : null}
    </>
  );
}

function buildAssistantContext(pathname: string, search: string) {
  const searchParams = Object.fromEntries(new URLSearchParams(search).entries());
  const deviceMatch = pathname.match(/^\/devices\/([^/]+)/);
  const page = deviceMatch ? "device-details" : pathname.startsWith("/alerts") ? "alerts" : pathname.startsWith("/devices") ? "devices" : pathname.startsWith("/settings") ? "settings" : "app";
  return {
    page,
    pageLabel: formatEnum(page),
    route: pathname,
    searchParams,
    deviceId: deviceMatch ? decodeURIComponent(deviceMatch[1]) : undefined,
  };
}

function formatEnum(value: string) {
  return value
    .toLowerCase()
    .split(/[_\s-]+/)
    .filter(Boolean)
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join(" ");
}
