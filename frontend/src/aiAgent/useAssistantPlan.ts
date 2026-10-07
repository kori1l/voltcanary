import { FormEvent, useEffect, useRef, useState } from "react";
import { AgentPlan, createAgentPlan } from "./aiAgentApi";
import { inspectAssistantContext } from "./assistantContext";
export function useAssistantPlan(message: string, contextText: string, providerId?: string, modelId?: string) {
  const [plan,setPlan] = useState<AgentPlan | null>(null); const [error,setError] = useState<string | null>(null); const [isPlanning,setIsPlanning] = useState(false);
  const signature = JSON.stringify([message,contextText,providerId,modelId]); const current = useRef(signature); current.current = signature; const pending = useRef<string | null>(null);
  useEffect(() => { setPlan(null); setError(null); setIsPlanning(false); },[signature]);
  useEffect(() => () => { current.current = "unmounted"; }, []);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!message.trim() || pending.current === signature) return; pending.current = signature; setIsPlanning(true); setPlan(null); setError(null);
    try { const context = inspectAssistantContext(contextText); const result = await createAgentPlan(message.trim(),context,providerId || undefined,modelId || undefined); if (current.current === signature) setPlan(result); }
    catch (error) { if (current.current === signature) setError(error instanceof Error ? error.message : "Planning failed"); }
    finally { if (pending.current === signature) pending.current = null; if (current.current === signature) setIsPlanning(false); }
  }
  return { plan,error,isPlanning,submit };
}
