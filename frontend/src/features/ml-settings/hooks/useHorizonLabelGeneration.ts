import { useEffect, useRef, useState } from "react";
import {
  generateHorizonLabels,
  getHorizonLabelGenerationStatus,
  MlHorizonLabelGenerationResponse,
} from "../../../analytics/mlModuleApi";
import { safeMlError } from "../../../analytics/mlSettingsApi";
import { draftScope, readPrivateLegacyDraft, readScopedDraft, saveScopedDraft, scopedDraftKey } from "../../../components/scopedDrafts";

export function useHorizonLabelGeneration({
  accountId,
  datasetVersionId,
  horizonDays,
  includePreventiveMaintenance,
  onChanged,
  setError,
}: {
  accountId: string | null;
  datasetVersionId: string;
  horizonDays: number;
  includePreventiveMaintenance: boolean;
  onChanged: (datasetVersionId: string) => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const scope = draftScope(accountId, `horizon-labels.${datasetVersionId}`, `${horizonDays}.failure-outcome-v1.${includePreventiveMaintenance}`);
  const scopeKey = scope ? scopedDraftKey(scope) : `volatile.${accountId}.${datasetVersionId}.${horizonDays}.${includePreventiveMaintenance}`;
  const generation = useRef(0);
  const [storedJob, setStoredJob] = useState<{ scope: string; job: MlHorizonLabelGenerationResponse | null }>({ scope: scopeKey, job: null });
  const generationJob = storedJob.scope === scopeKey ? storedJob.job : null;
  const setGenerationJob = (job: MlHorizonLabelGenerationResponse | null) => setStoredJob({ scope: scopeKey, job });
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
    generation.current++;
    setGenerationJob(null);
    setGenerationStatusMessage(null);
    if (!datasetVersionId) {
      return;
    }
    const decode = (value: Record<string, unknown>): MlHorizonLabelGenerationResponse | null => typeof value.jobId === "string" && value.datasetVersionId === datasetVersionId && value.horizonDays === horizonDays && value.includePreventiveMaintenance === includePreventiveMaintenance && ["QUEUED", "RUNNING", "COMPLETED", "FAILED"].includes(String(value.status)) ? { ...value, result: null, failureReason: null, message: null } as unknown as MlHorizonLabelGenerationResponse : null;
    const job = readScopedDraft(scope, decode) ?? readPrivateLegacyDraft(scope, horizonJobStorageKey(datasetVersionId, horizonDays, includePreventiveMaintenance), decode, jobFields);
    if (job) { setGenerationJob(job); setGenerationStatusMessage(`Last recorded state: ${horizonJobStatusMessage(job)}`); }
  }, [scopeKey]);

  async function generateLabels() {
    const epoch = generation.current;
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
      await applyGenerationJob(job, epoch);
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
    const epoch = generation.current;
    if (!generationJob?.jobId) {
      if (manual) {
        setGenerationStatusMessage("Start label generation before checking status.");
      }
      return;
    }
    try {
      const job = await getHorizonLabelGenerationStatus(generationJob.jobId);
      await applyGenerationJob(job, epoch);
    } catch {
      if (manual) {
        setGenerationStatusMessage("Status refresh failed. Last known generation state is still shown.");
      }
    }
  }

  async function applyGenerationJob(job: MlHorizonLabelGenerationResponse, epoch: number) {
    if (job.datasetVersionId !== datasetVersionId || job.horizonDays !== horizonDays || job.includePreventiveMaintenance !== includePreventiveMaintenance) return;
    saveScopedDraft(scope, job, jobFields);
    if (epoch !== generation.current) return;
    setGenerationJob(job);
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
const jobFields = ["jobId", "datasetVersionId", "status", "submittedAt", "startedAt", "completedAt", "progress", "horizonDays", "generationVersion", "includePreventiveMaintenance", "inputLineageHash"];

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
