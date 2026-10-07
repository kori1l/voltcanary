import { useRef, useState } from "react";
import { Link } from "react-router-dom";
import { useActionConfirmation } from "../components/useActionConfirmation";
import { AgentPlan, ExecutePlanResponse, executeAgentPlan } from "./aiAgentApi";
import { redactAssistantDetails } from "./assistantContext";
export function planMutates(plan: AgentPlan) { return plan.confirmationRequired || plan.actions.some((action) => action.requiresConfirmation || /create|update|delete|save|rotate|start|stop|disable|enable/i.test(action.tool)); }
export function PlanReview({ plan, onOpen }: { plan: AgentPlan; onOpen?: () => void }) {
  const confirmation = useActionConfirmation(); const [execution,setExecution] = useState<ExecutePlanResponse | null>(null); const [busy,setBusy] = useState(false); const [error,setError] = useState<string | null>(null);
  const mutating = planMutates(plan);
  const pending = useRef(false);
  async function execute() {
    if (pending.current || busy || execution?.planId === plan.planId) return;
    if (mutating && await confirmation.confirm({ title: "Confirm and execute plan", object: plan.summary, impact: plan.actions.map((action,index) => `${index + 1}. ${action.title}: ${action.description}`).join("\n"), dependencies: "Only this reviewed plan is executed. The server enforces tool authorization and records action results." }) === null) return;
    if (pending.current) return;
    pending.current = true; setBusy(true); setError(null);
    try { setExecution(await executeAgentPlan(plan.planId,mutating)); } catch (error) { setError(error instanceof Error ? error.message : "Plan execution failed"); } finally { pending.current = false; setBusy(false); }
  }
  return <section className="panel form-stack">{confirmation.dialog}<h2>{plan.summary}</h2><p>{plan.explanation}</p><p>{mutating ? "This plan changes objects. Review each step before execution." : "Read-only plan."}</p>{plan.actions.map((action,index) => <article className="registry-row" key={action.id}><div><strong>{index+1}. {action.title}</strong><p>{action.description}</p><small>{action.requiresConfirmation ? "Changes require review" : "Read-only action"}</small><details><summary>Technical parameters</summary><pre>{JSON.stringify(redactAssistantDetails(action.parameters),null,2)}</pre></details></div></article>)}<button className="primary-button" type="button" disabled={busy || execution?.planId === plan.planId} onClick={() => void execute()}>{busy ? "Executing..." : execution?.planId === plan.planId ? "Execution recorded" : mutating ? "Review and execute" : "Run read-only"}</button>{error ? <p className="error-message" role="alert">{error}</p> : null}{execution ? <section aria-label="Plan execution"><h3>Result: {execution.status}</h3>{execution.actions.map((action) => <article className="registry-row" key={action.actionId}><div><strong>{plan.actions.find((step) => step.id === action.actionId)?.title ?? action.tool} · {action.status}</strong>{action.error ? <p>{action.error}</p> : null}{typeof action.result?.route === "string" && action.result.route.startsWith("/") && !action.result.route.startsWith("//") ? <Link to={action.result.route} onClick={onOpen}>Open result</Link> : null}</div></article>)}</section> : null}<details><summary>Advanced diagnostics</summary>{plan.safetyNotes.map((note) => <p key={note}>{note}</p>)}<p>Provider: {plan.providerName ?? plan.providerType ?? "Unavailable"}. Fallback: {plan.fallbackUsed === undefined ? "Unknown" : plan.fallbackUsed ? "Used" : "Not used"}.</p></details></section>;
}
