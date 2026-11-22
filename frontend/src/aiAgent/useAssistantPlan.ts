import { FormEvent, useEffect, useRef, useState, useSyncExternalStore } from "react";
import { AgentPlan, clarifyAgentPlan, createAgentPlan, getAgentPlan } from "./aiAgentApi";
import { inspectAssistantContext } from "./assistantContext";
import { sessionIdentityRevision, subscribeSession } from "../api/client";
import { lastAssistantTask, rememberAssistantTask } from "./assistantTaskRecovery";
export function useAssistantPlan(
  message: string,
  contextText: string,
  providerId?: string,
  modelId?: string,
) {
  const [plan, setPlan] = useState<AgentPlan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [isPlanning, setIsPlanning] = useState(false);
  const identity = useSyncExternalStore(
    subscribeSession,
    sessionIdentityRevision,
    sessionIdentityRevision,
  );
  const signature = JSON.stringify([message, contextText, providerId, modelId, identity]);
  const current = useRef(signature);
  current.current = signature;
  const pending = useRef<string | null>(null);
  useEffect(() => {
    setPlan(null);
    setError(null);
    setIsPlanning(false);
  }, [signature]);
  useEffect(() => {
    current.current = signature;
    return () => {
      current.current = "unmounted";
    };
  }, [signature]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!message.trim() || pending.current === signature) return;
    pending.current = signature;
    setIsPlanning(true);
    setPlan(null);
    setError(null);
    try {
      const context = inspectAssistantContext(contextText);
      const result = await createAgentPlan(
        message.trim(),
        context,
        providerId || undefined,
        modelId || undefined,
      );
      if (current.current === signature) {
        setPlan(result);
        rememberAssistantTask(result.planId);
      }
    } catch (error) {
      if (current.current === signature)
        setError(error instanceof Error ? error.message : "Planning failed");
    } finally {
      if (pending.current === signature) pending.current = null;
      if (current.current === signature) setIsPlanning(false);
    }
  }
  async function recover() {
    const id = lastAssistantTask();
    if (!id || pending.current === signature) return;
    pending.current = signature;
    setIsPlanning(true);
    setError(null);
    try {
      const result = await getAgentPlan(id);
      if (current.current === signature) setPlan(result);
    } catch (failure) {
      if (current.current === signature)
        setError(failure instanceof Error ? failure.message : "Task recovery failed");
    } finally {
      if (pending.current === signature) pending.current = null;
      if (current.current === signature) setIsPlanning(false);
    }
  }
  async function clarify(answer: string): Promise<boolean> {
    if (!plan || !answer.trim() || answer.length > 2000 || pending.current === signature)
      return false;
    const parent = plan.planId;
    pending.current = signature;
    setIsPlanning(true);
    setError(null);
    try {
      const result = await clarifyAgentPlan(parent, answer.trim());
      if (current.current !== signature) return false;
      setPlan(result);
      rememberAssistantTask(result.planId);
      return true;
    } catch (failure) {
      if (current.current === signature)
        setError(failure instanceof Error ? failure.message : "Clarification failed");
      return false;
    } finally {
      if (pending.current === signature) pending.current = null;
      if (current.current === signature) setIsPlanning(false);
    }
  }
  return {
    plan,
    error,
    isPlanning,
    submit,
    recover,
    clarify,
    canRecover: Boolean(lastAssistantTask()),
  };
}
