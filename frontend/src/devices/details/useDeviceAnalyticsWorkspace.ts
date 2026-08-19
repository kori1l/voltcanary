import { useState } from "react";
import {
  AnomalyResponse,
  AnalyticsJobResponse,
  BaselineResponse,
  getAnalyticsJob,
  getDeviceAnomalies,
  getDeviceBaseline,
  getDeviceRecommendations,
  getDeviceRisk,
  getLatestPrediction,
  PredictionResponse,
  recalculateAnalytics,
  RecommendationResponse,
  RiskResponse,
} from "../../analytics/analyticsApi";
import { delay } from "./deviceDetailsFormatting";

export function useDeviceAnalyticsWorkspace(deviceId: string | undefined) {
  const [baseline, setBaseline] = useState<BaselineResponse | null>(null);
  const [risk, setRisk] = useState<RiskResponse | null>(null);
  const [anomalies, setAnomalies] = useState<AnomalyResponse[]>([]);
  const [recommendations, setRecommendations] = useState<RecommendationResponse[]>([]);
  const [prediction, setPrediction] = useState<PredictionResponse | null>(null);
  const [analyticsJob, setAnalyticsJob] = useState<AnalyticsJobResponse | null>(null);
  const [isRecalculating, setIsRecalculating] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function loadAnalytics(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return null;
    }
    const [baselineResponse, riskResponse, anomaliesResponse, recommendationsResponse, predictionResponse] = await Promise.all([
      getDeviceBaseline(currentDeviceId).catch(() => null),
      getDeviceRisk(currentDeviceId).catch(() => null),
      getDeviceAnomalies(currentDeviceId).catch(() => []),
      getDeviceRecommendations(currentDeviceId).catch(() => []),
      getLatestPrediction(currentDeviceId).catch(() => null),
    ]);
    setBaseline(baselineResponse);
    setRisk(riskResponse);
    setAnomalies(anomaliesResponse);
    setRecommendations(recommendationsResponse);
    setPrediction(predictionResponse);
    return riskResponse;
  }

  async function enqueueAndWaitForAnalyticsJob(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return null;
    }
    const response = await recalculateAnalytics(currentDeviceId);
    if (!response.jobId) {
      return null;
    }
    let job = await getAnalyticsJob(currentDeviceId, response.jobId).catch(() => null);
    if (job) {
      setAnalyticsJob(job);
    }
    for (let attempt = 0; attempt < 12 && job && ["PENDING", "RUNNING"].includes(job.status); attempt += 1) {
      await delay(1000);
      job = await getAnalyticsJob(currentDeviceId, response.jobId).catch(() => job);
      if (job) {
        setAnalyticsJob(job);
      }
    }
    if (job?.status === "FAILED") {
      throw new Error(job.errorMessage ?? "Analytics recalculation failed");
    }
    return job;
  }

  async function recalculateDeviceAnalytics() {
    if (!deviceId) {
      return;
    }
    setIsRecalculating(true);
    setError(null);
    try {
      await enqueueAndWaitForAnalyticsJob(deviceId);
      await loadAnalytics(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to recalculate risk");
    } finally {
      setIsRecalculating(false);
    }
  }

  return {
    baseline,
    risk,
    anomalies,
    recommendations,
    prediction,
    analyticsJob,
    isRecalculating,
    error,
    setError,
    loadAnalytics,
    enqueueAndWaitForAnalyticsJob,
    recalculateDeviceAnalytics,
  };
}
