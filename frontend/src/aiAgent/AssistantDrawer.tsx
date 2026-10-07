import { useAssistantPlan } from "./useAssistantPlan";
import { buildAssistantContext } from "./assistantContext";
import { AssistantContextSummary } from "./AssistantContextSummary";
import { PlanReview } from "./PlanReview";
import { useMemo, useState } from "react";
import { useLocation } from "react-router-dom";
import { Dialog } from "../components/Dialog";


const DEFAULT_PROMPTS = [
  "Explain the current issue and suggest the next step.",
  "Create a demo device with realistic telemetry.",
  "Suggest an alert rule for this device.",
];

export function AssistantDrawer() {
  const location = useLocation();
  const [isOpen, setIsOpen] = useState(false);
  const [message, setMessage] = useState(DEFAULT_PROMPTS[0]);
  const context = useMemo(() => buildAssistantContext(location.pathname, location.search), [location.pathname, location.search]);

  const { plan, error, isPlanning, submit } = useAssistantPlan(message, JSON.stringify(context));

  return (
    <>
      <button className="assistant-button" type="button" onClick={() => setIsOpen(true)}>
        AI
      </button>
      {isOpen ? (
        <Dialog title="AI assistant" onClose={() => setIsOpen(false)} busy={isPlanning} guardChanges>

            <AssistantContextSummary context={context} />
            <form className="assistant-compose" onSubmit={submit}>
              <label>Request<textarea value={message} onChange={(event) => setMessage(event.target.value)} rows={4} /></label>
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

            {plan ? <PlanReview key={plan.planId} plan={plan} onOpen={() => setIsOpen(false)} /> : null}
            <details className="assistant-advanced">
              <summary>Advanced</summary>
              <pre>{JSON.stringify(context, null, 2)}</pre>
            </details>
        </Dialog>
      ) : null}
    </>
  );
}
