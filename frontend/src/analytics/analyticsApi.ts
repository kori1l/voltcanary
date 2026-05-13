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
  modelVersion: string;
  anomalyScore: number;
  degradationScore: number;
  confidence: number;
  explanations: string[];
  createdAt: string;
}

export interface RecalculateResponse {
  status: string;
  deviceId: string;
  baselineId: string;
  anomalyCount: number;
  riskScore: number;
  riskLevel: string;
  recommendationCount: number;
}

export function recalculateAnalytics(deviceId: string) {
  return apiRequest<RecalculateResponse>(`/analytics/devices/${deviceId}/recalculate`, {
    method: "POST",
  });
}

export function getDeviceBaseline(deviceId: string) {
  return apiRequest<BaselineResponse>(`/analytics/devices/${deviceId}/baseline`);
}

export function getDeviceAnomalies(deviceId: string) {
  return apiRequest<AnomalyResponse[]>(`/analytics/devices/${deviceId}/anomalies`);
}

export function getDeviceRisk(deviceId: string) {
  return apiRequest<RiskResponse>(`/analytics/devices/${deviceId}/risk`);
}

export function getDeviceRecommendations(deviceId: string) {
  return apiRequest<RecommendationResponse[]>(`/analytics/devices/${deviceId}/recommendations`);
}

export function getLatestPrediction(deviceId: string) {
  return apiRequest<PredictionResponse>(`/analytics/devices/${deviceId}/predictions/latest`);
}
