import { apiRequest } from "../api/client";

export interface AgentAction {
  id: string;
  tool: string;
  title: string;
  description: string;
  requiresConfirmation: boolean;
  parameters: Record<string, unknown>;
}

export interface AgentPlan {
  planId: string;
  summary: string;
  explanation: string;
  confirmationRequired: boolean;
  safetyNotes: string[];
  actions: AgentAction[];
}

export interface ActionExecution {
  actionId: string;
  tool: string;
  status: string;
  result: Record<string, unknown>;
  error: string | null;
}

export interface ExecutePlanResponse {
  planId: string;
  status: string;
  actions: ActionExecution[];
}

export interface AgentProviderStatus {
  provider: string;
  model: string;
  enabled: boolean;
  fallback: string;
}

export function createAgentPlan(message: string, context: Record<string, unknown> = {}) {
  return apiRequest<AgentPlan>("/ai-agent/plans", {
    method: "POST",
    body: JSON.stringify({ message, context }),
  });
}

export function executeAgentPlan(planId: string, confirmed: boolean) {
  return apiRequest<ExecutePlanResponse>(`/ai-agent/plans/${planId}/execute`, {
    method: "POST",
    body: JSON.stringify({ confirmed }),
  });
}

export function listAgentTools() {
  return apiRequest<string[]>("/ai-agent/tools");
}

export function getAgentProviderStatus() {
  return apiRequest<AgentProviderStatus>("/ai-agent/provider");
}
