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
  providerType?: string;
  providerName?: string;
  modelId?: string;
  providerId?: string | null;
  modelConfigId?: string | null;
  fallbackUsed?: boolean;
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

export function createAgentPlan(message: string, context: Record<string, unknown> = {}, providerId?: string, modelConfigId?: string) {
  return apiRequest<AgentPlan>("/ai-agent/plans", {
    method: "POST",
    body: JSON.stringify({ message, context, providerId, modelConfigId }),
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
