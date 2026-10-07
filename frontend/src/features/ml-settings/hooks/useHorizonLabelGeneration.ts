import { useEffect, useRef, useState } from "react";
import {
  generateHorizonLabels,
  getHorizonLabelGenerationStatus,
  MlHorizonLabelGenerationResponse,
} from "../../../analytics/mlModuleApi";
import { safeMlError } from "../../../analytics/mlSettingsApi";

export function useHorizonLabelGeneration({
  datasetVersionId,
  horizonDays,
  includePreventiveMaintenance,
  onChanged,
  setError,
}: {
  datasetVersionId: string;
  horizonDays: number;
  includePreventiveMaintenance: boolean;
  onChanged: (datasetVersionId: string) => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const [generationJob, setGenerationJob] = useState<MlHorizonLabelGenerationResponse | null>(null);
  const [generationStatusMessage, setGenerationStatusMessage] = useState<string | null>(null);
  const starting = useRef(false);
  const [generationStarting, setGenerationStarting] = useState(false);

  useEffect(() => {
    if (!isActiveHorizonJob(generationJob)) {
      return undefined;
    }
    const intervalId = window.setInterval(() => {
      void refreshGenerationStatus(false);
    }, 2500);
    return () => window.clearInterval(intervalId);
  }, [generationJob?.jobId, generationJob?.status]);

  useEffect(() => {
    if (!datasetVersionId) {
      return;
    }
    const stored = window.localStorage.getItem(horizonJobStorageKey(datasetVersionId, horizonDays, includePreventiveMaintenance));
    if (!stored) {
      return;
    }
    try {
      const job = JSON.parse(stored) as MlHorizonLabelGenerationResponse;
      if (job.datasetVersionId === datasetVersionId) {
        setGenerationJob(job);
        setGenerationStatusMessage(horizonJobStatusMessage(job));
      }
    } catch {
      window.localStorage.removeItem(horizonJobStorageKey(datasetVersionId, horizonDays, includePreventiveMaintenance));
    }
  }, [datasetVersionId, horizonDays, includePreventiveMaintenance]);

  async function generateLabels() {
    if (starting.current || isActiveHorizonJob(generationJob)) return;
    if (!datasetVersionId) {
      setError("Select a dataset version before generating labels");
      return;
    }
    setGenerationStatusMessage("Starting horizon label generation.");
    starting.current = true;
    setGenerationStarting(true);
    setError(null);
    try {
      const job = await generateHorizonLabels({
        datasetVersionId,
        horizonDays,
        generationVersion: "failure-outcome-v1",
        includePreventiveMaintenance,
      });
      await applyGenerationJob(job);
    } catch (error) {
      if (isGatewayTimeout(error)) {
        setGenerationStatusMessage("Label generation start timed out before a job id was returned. Retry start or refresh labels before training.");
        return;
      }
      setGenerationStatusMessage(null);
      setError(safeMlError(error));
    } finally {
      starting.current = false;
      setGenerationStarting(false);
    }
  }

  async function refreshGenerationStatus(manual = true) {
    if (!generationJob?.jobId) {
      if (manual) {
        setGenerationStatusMessage("Start label generation before checking status.");
      }
      return;
    }
    try {
      const job = await getHorizonLabelGenerationStatus(generationJob.jobId);
      await applyGenerationJob(job);
    } catch {
      if (manual) {
        setGenerationStatusMessage("Status refresh failed. Last known generation state is still shown.");
      }
    }
  }

  async function applyGenerationJob(job: MlHorizonLabelGenerationResponse) {
    setGenerationJob(job);
    window.localStorage.setItem(
      horizonJobStorageKey(job.datasetVersionId, job.horizonDays, job.includePreventiveMaintenance),
      JSON.stringify(job),
    );
    if (job.status === "COMPLETED" && job.result) {
      setGenerationStatusMessage(horizonJobStatusMessage(job));
      await onChanged(job.datasetVersionId);
      return;
    }
    setGenerationStatusMessage(horizonJobStatusMessage(job));
  }

  return {
    generationJob,
    generationStatusMessage,
    generationResult: generationJob?.result ?? null,
    generationActive: generationStarting || isActiveHorizonJob(generationJob),
    generateLabels,
    refreshGenerationStatus,
  };
}

function isGatewayTimeout(error: unknown) {
  return error instanceof Error && /gateway timeout|504|timeout/i.test(error.message);
}

function isActiveHorizonJob(job: MlHorizonLabelGenerationResponse | null) {
  return job?.status === "QUEUED" || job?.status === "RUNNING";
}

function horizonJobStorageKey(datasetVersionId: string, horizonDays: number, includePreventiveMaintenance: boolean) {
  return `voltcanary.horizonLabelJob.${datasetVersionId}.${horizonDays}.failure-outcome-v1.${includePreventiveMaintenance}`;
}

function horizonJobStatusMessage(job: MlHorizonLabelGenerationResponse) {
  if (job.status === "COMPLETED" && job.result) {
    return `Generated ${job.result.generatedLabels} labels. Review coverage before training.`;
  }
  if (job.status === "FAILED") {
    return job.failureReason ? `Generation failed: ${job.failureReason}` : "Generation failed.";
  }
  return `${humanizeKey(job.status)} horizon label generation${job.progress !== null ? ` (${job.progress}%)` : ""}.`;
}

function humanizeKey(value: string) {
  const text = value.replace(/([a-z])([A-Z])/g, "$1 $2").toLowerCase();
  return text.replace(/^./, (match) => match.toUpperCase());
}
