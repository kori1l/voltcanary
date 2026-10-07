import { useRef, useState } from "react";
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
import { useSectionResource } from "../../components/useSectionResource";

export function useDeviceAnalyticsWorkspace(deviceId: string | undefined) {
  const baselineSection = useSectionResource<BaselineResponse>(deviceId);
  const riskSection = useSectionResource<RiskResponse>(deviceId);
  const anomaliesSection = useSectionResource<AnomalyResponse[]>(deviceId);
  const recommendationsSection = useSectionResource<RecommendationResponse[]>(deviceId);
  const predictionSection = useSectionResource<PredictionResponse>(deviceId);
  const baseline = baselineSection.data;
  const risk = riskSection.data;
  const anomalies = anomaliesSection.data ?? [];
  const recommendations = recommendationsSection.data ?? [];
  const prediction = predictionSection.data;
  const [analyticsJob, setAnalyticsJob] = useState<AnalyticsJobResponse | null>(null);
  const [isRecalculating, setIsRecalculating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const recalculationPending = useRef(false);

  async function loadAnalytics(currentDeviceId = deviceId) {
    if (!currentDeviceId) {
      return null;
    }
    const [, riskResponse] = await Promise.all([
      baselineSection.load(() => getDeviceBaseline(currentDeviceId)),
      riskSection.load(() => getDeviceRisk(currentDeviceId)),
      anomaliesSection.load(() => getDeviceAnomalies(currentDeviceId), (data) => !data.length),
      recommendationsSection.load(() => getDeviceRecommendations(currentDeviceId), (data) => !data.length),
      predictionSection.load(() => getLatestPrediction(currentDeviceId)),
    ]);
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
    let job: AnalyticsJobResponse = { id: response.jobId, deviceId: currentDeviceId, jobType: "RECALCULATE", status: "PENDING", createdAt: new Date().toISOString(), startedAt: null, completedAt: null, errorMessage: null };
    setAnalyticsJob(job);
    job = await getAnalyticsJob(currentDeviceId, response.jobId).catch(() => job);
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
    if (!deviceId || recalculationPending.current || (analyticsJob && ["PENDING", "RUNNING"].includes(analyticsJob.status))) {
      return;
    }
    setIsRecalculating(true);
    recalculationPending.current = true;
    setError(null);
    try {
      await enqueueAndWaitForAnalyticsJob(deviceId);
      await loadAnalytics(deviceId);
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to recalculate risk");
    } finally {
      setIsRecalculating(false);
      recalculationPending.current = false;
    }
  }

  async function refreshAnalyticsJob() {
    if (!deviceId || !analyticsJob) return;
    try { setAnalyticsJob(await getAnalyticsJob(deviceId, analyticsJob.id)); setError(null); }
    catch (error) { setError(error instanceof Error ? error.message : "Job status unavailable; retaining last known state."); }
  }

  return {
    sections: [ { label: "baseline", resource: baselineSection }, { label: "risk", resource: riskSection }, { label: "anomalies", resource: anomaliesSection }, { label: "recommendations", resource: recommendationsSection }, { label: "prediction", resource: predictionSection } ],
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
    refreshAnalyticsJob,
  };
}
