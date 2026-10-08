import { apiRequest } from "../api/client";

export interface BaselineResponse {
  deviceId: string;
  metric: string;
  period: string;
  mean: number;
  median: number;
  p90: number;
  min: number;
  max: number;
  sampleCount: number;
  calculatedAt: string;
}

export interface AnomalyResponse {
  id: string;
  deviceId: string;
  type: string;
  severity: string;
  score: number;
  description: string;
  detectedAt: string;
}

export interface RiskResponse {
  deviceId: string;
  score: number;
  level: string;
  explanations: string[];
  calculatedAt: string;
}

export interface RecommendationResponse {
  id: string;
  deviceId: string;
  title: string;
  description: string;
  priority: string;
  createdAt: string;
}

export interface PredictionResponse {
  deviceId: string;
  modelVersionId?: string | null;
  modelFamily?: string | null;
  modelName: string;
  modelVersion: string;
  modelType?: string;
  modelStatus?: string;
  predictionStatus: string;
  policySource?: string | null;
  deploymentMode?: string | null;
  resolvedDeviceType?: string | null;
  canaryPercent?: number | null;
  canaryIncluded?: boolean | null;
  predictionPath?: string | null;
  fallbackReason?: string | null;
  shadowPrediction?: boolean;
  anomalyScore: number;
  degradationScore: number;
  failureRiskScore?: number | null;
  failureHorizonDays?: number | null;
  failureRiskBand?: string | null;
  confidence: number;
  inputHash: string;
  riskDrivers?: Array<Record<string, unknown>>;
  labelCoverage?: number | null;
  trainingDataWindow?: Record<string, unknown>;
  signals: Array<Record<string, unknown>>;
  explanations: string[];
  recommendedActions: string[];
  createdAt: string;
}

export interface FeatureSetResponse {
  id: string;
  deviceId: string;
  windowStart: string;
  windowEnd: string;
  features: Record<string, unknown>;
}

export interface RecalculateResponse {
  status: string;
  deviceId: string;
  jobId: string;
  baselineId: string | null;
  anomalyCount: number;
  riskScore: number;
  riskLevel: string;
  recommendationCount: number;
}

export interface AnalyticsJobResponse {
  id: string;
  deviceId: string;
  jobType: string;
  status: string;
  createdAt: string;
  startedAt: string | null;
  completedAt: string | null;
  errorMessage: string | null;
}

export function recalculateAnalytics(deviceId: string) {
  return apiRequest<RecalculateResponse>(`/analytics/devices/${deviceId}/recalculate`, {
    method: "POST",
  });
}

export function getAnalyticsJobs(deviceId: string) {
  return apiRequest<AnalyticsJobResponse[]>(`/analytics/devices/${deviceId}/jobs`);
}

export function getAnalyticsJob(deviceId: string, jobId: string) {
  return apiRequest<AnalyticsJobResponse>(`/analytics/devices/${deviceId}/jobs/${jobId}`);
}

export function getDeviceBaseline(deviceId: string) {
  return apiRequest<BaselineResponse>(`/analytics/devices/${deviceId}/baseline`);
}

export function getDeviceAnomalies(deviceId: string) {
  return apiRequest<AnomalyResponse[]>(`/analytics/devices/${deviceId}/anomalies`);
}

export function getDeviceFeatures(deviceId: string) {
  return apiRequest<FeatureSetResponse[]>(`/analytics/devices/${deviceId}/features`);
}

export function getDeviceRisk(deviceId: string, signal?: AbortSignal) {
  return apiRequest<RiskResponse>(`/analytics/devices/${deviceId}/risk`, { signal });
}

export function getDeviceRecommendations(deviceId: string) {
  return apiRequest<RecommendationResponse[]>(`/analytics/devices/${deviceId}/recommendations`);
}

export function getLatestPrediction(deviceId: string) {
  return apiRequest<PredictionResponse>(`/analytics/devices/${deviceId}/predictions/latest`);
}
