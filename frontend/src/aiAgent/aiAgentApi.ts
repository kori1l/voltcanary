import { apiRequest, prepareAiSession } from "../api/client";

export interface AgentAction {
  id: string;
  tool: string;
  title: string;
  description: string;
  requiresConfirmation: boolean;
  parameters: Record<string, unknown>;
}

export interface AgentPlan {
  task?: DashboardTaskSummary | null;
  restoredFromReviewId?: string | null;
  planId: string;
  previousReviewIds?: string[];
  summary: string;
  explanation: string;
  confirmationRequired: boolean;
  safetyNotes: string[];
  actions: AgentAction[];
  providerType?: string;
  providerName?: string;
  modelId?: string;
  providerId?: string | null;
  modelConfigId?: string | null;
  fallbackUsed?: boolean;
  version?: number;
  payloadDigest?: string;
  expiresAt?: string;
  planningStatus?: "READY" | "NEEDS_INPUT" | "FAILED";
  questions?: string[];
  clarification?: {missingFields:string[];suggestedReply:string;suggestionLabel:string;suggestedValues:Record<string,string|number|boolean>;requiresExplicitChoice?:boolean}|null;
  executionStatus?: string;
  execution?: ExecutePlanResponse | null;
}

export interface DashboardTaskSummary {
  taskId: string;
  branchId: string;
  revision: number;
  kind: "DASHBOARD";
  originalRequest: string;
  name: string | null;
  lastAcceptedReviewId: string | null;
  status: "NEW" | "PENDING" | "READY" | "NEEDS_INPUT" | "FAILED";
  failureCategory: string | null;
  expiresAt: string;
  selectionStatus?: "UNVERIFIED" | "AVAILABLE" | "UNAVAILABLE";
  pending: {changeId:string;turnId:string;request:string;disposition:"PENDING"|"APPLIED"|"FAILED"|"SUPERSEDED"}[];
}
export interface DashboardTurnMetadata {
  requestId: string;
  taskId?: string;
  branchId?: string;
  expectedTaskRevision?: number;
  baseReviewId?: string;
  acceptedSuggestion?: boolean;
}
export async function getDashboardTask(taskId:string, branchId?:string) {
  const result=await apiRequest<{task:DashboardTaskSummary;lastReview:AgentPlan|null}>(
    `/ai-agent/dashboard-tasks/${encodeURIComponent(taskId)}${branchId?`?branchId=${encodeURIComponent(branchId)}`:""}`);
  if(!result?.task||typeof result.task.taskId!=="string"||typeof result.task.branchId!=="string"||!Number.isInteger(result.task.revision)){
    throw new Error("Не удалось восстановить состояние задачи. Повторите восстановление.");
  }
  return result;
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

export interface SuggestedModel {
  modelId: string;
  displayName: string;
}

export interface ModelProviderCapability {
  providerType: string;
  displayName: string;
  authType: string;
  defaultBaseUrl: string;
  endpointStyle: string;
  supportsCustomBaseUrl: boolean;
  apiKeyRequired: boolean;
  supportsJson: boolean;
  supportsTools: boolean;
  suggestedModels: SuggestedModel[];
}

export interface ModelConfig {
  id: string;
  providerId: string;
  displayName: string;
  modelId: string;
  enabled: boolean;
  isDefault: boolean;
  temperature: number | null;
  topP: number | null;
  maxOutputTokens: number | null;
  timeoutMs: number | null;
  supportsTools: boolean;
  supportsJson: boolean;
  responseFormat: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface ModelProvider {
  id: string;
  name: string;
  providerType: string;
  status: string;
  isDefault: boolean;
  baseUrl: string | null;
  authType: string;
  maskedSecretHint: string | null;
  lastTestStatus: string | null;
  lastTestMessage: string | null;
  lastTestAt: string | null;
  models: ModelConfig[];
  createdAt: string;
  updatedAt: string;
}

export interface CreateModelProviderRequest {
  name: string;
  providerType: string;
  baseUrl?: string;
  apiKey?: string;
  modelId: string;
  modelDisplayName?: string;
  temperature?: number;
  topP?: number;
  maxOutputTokens?: number;
  timeoutMs?: number;
  supportsTools?: boolean;
  supportsJson?: boolean;
  makeDefault?: boolean;
}

export interface UpdateModelProviderRequest {
  name?: string;
  baseUrl?: string;
  status?: string;
}

export interface CreateModelConfigRequest {
  displayName: string;
  modelId: string;
  enabled?: boolean;
  makeDefault?: boolean;
  temperature?: number;
  topP?: number;
  maxOutputTokens?: number;
  timeoutMs?: number;
  supportsTools?: boolean;
  supportsJson?: boolean;
  responseFormat?: string;
}

export interface UpdateModelConfigRequest {
  displayName?: string;
  modelId?: string;
  enabled?: boolean;
  temperature?: number;
  topP?: number;
  maxOutputTokens?: number;
  timeoutMs?: number;
  supportsTools?: boolean;
  supportsJson?: boolean;
  responseFormat?: string;
}

export interface TestModelProviderResponse {
  status: string;
  message: string;
  providerType: string;
  modelId: string;
  latencyMs: number | null;
  testedAt: string;
}

export async function clarifyAgentPlan(planId: string, message: string, metadata?: DashboardTurnMetadata) {
  await prepareAiSession();
  return apiRequest<AgentPlan>(`/ai-agent/plans/${encodeURIComponent(planId)}/clarifications`, {
    method: "POST", body: JSON.stringify({message,...metadata}),
  });
}

export async function createAgentPlan(message: string, context: Record<string, unknown> = {}, providerId?: string, modelConfigId?: string, metadata?:DashboardTurnMetadata) {
  await prepareAiSession();
  return apiRequest<AgentPlan>("/ai-agent/plans", {
    method: "POST",
    body: JSON.stringify({ message, context, providerId, modelConfigId,...metadata }),
  });
}

export function executeAgentPlan(planId: string, confirmed: boolean, expectedVersion?: number, payloadDigest?: string) {
  return apiRequest<ExecutePlanResponse>(`/ai-agent/plans/${planId}/execute`, {
    method: "POST",
    body: JSON.stringify({ confirmed, expectedVersion, payloadDigest }),
  });
}

export function getAgentPlan(planId: string) {
  return apiRequest<AgentPlan>(`/ai-agent/plans/${encodeURIComponent(planId)}`);
}

export function listAgentTools() {
  return apiRequest<string[]>("/ai-agent/tools");
}

export function getAgentProviderStatus() {
  return apiRequest<AgentProviderStatus>("/ai-agent/provider");
}

export function listModelProviderCapabilities() {
  return apiRequest<ModelProviderCapability[]>("/ai-agent/model-provider-capabilities");
}

export function listModelProviders() {
  return apiRequest<ModelProvider[]>("/ai-agent/model-providers");
}

export function createModelProvider(request: CreateModelProviderRequest) {
  return apiRequest<ModelProvider>("/ai-agent/model-providers", {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function updateModelProvider(providerId: string, request: UpdateModelProviderRequest) {
  return apiRequest<ModelProvider>(`/ai-agent/model-providers/${providerId}`, {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

export function deleteModelProvider(providerId: string) {
  return apiRequest<void>(`/ai-agent/model-providers/${providerId}`, { method: "DELETE" });
}

export function setDefaultModelProvider(providerId: string) {
  return apiRequest<ModelProvider>(`/ai-agent/model-providers/${providerId}/set-default`, { method: "POST" });
}

export function testModelProvider(providerId: string) {
  return apiRequest<TestModelProviderResponse>(`/ai-agent/model-providers/${providerId}/test`, { method: "POST" });
}

export function rotateModelProviderSecret(providerId: string, apiKey: string) {
  return apiRequest<ModelProvider>(`/ai-agent/model-providers/${providerId}/rotate-secret`, {
    method: "POST",
    body: JSON.stringify({ apiKey }),
  });
}

export function listModelConfigs(providerId: string) {
  return apiRequest<ModelConfig[]>(`/ai-agent/model-providers/${providerId}/models`);
}

export function createModelConfig(providerId: string, request: CreateModelConfigRequest) {
  return apiRequest<ModelConfig>(`/ai-agent/model-providers/${providerId}/models`, {
    method: "POST",
    body: JSON.stringify(request),
  });
}

export function updateModelConfig(providerId: string, modelConfigId: string, request: UpdateModelConfigRequest) {
  return apiRequest<ModelConfig>(`/ai-agent/model-providers/${providerId}/models/${modelConfigId}`, {
    method: "PUT",
    body: JSON.stringify(request),
  });
}

export function deleteModelConfig(providerId: string, modelConfigId: string) {
  return apiRequest<void>(`/ai-agent/model-providers/${providerId}/models/${modelConfigId}`, { method: "DELETE" });
}

export function setDefaultModelConfig(providerId: string, modelConfigId: string) {
  return apiRequest<ModelConfig>(`/ai-agent/model-providers/${providerId}/models/${modelConfigId}/set-default`, { method: "POST" });
}
