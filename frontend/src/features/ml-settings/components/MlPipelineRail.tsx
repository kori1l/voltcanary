import { DatasetPipelineStep } from "../../../features/ml-datasets/model/datasetWorkbench.types";

import { WorkflowMode } from "../model/mlConsolePresentation";
export function WorkflowPipelineRail(props: {
  mode: WorkflowMode;
  onModeChange: (mode: WorkflowMode) => void;
  steps: DatasetPipelineStep[];
  onStep: (id: string) => void;
}) {
  return (
    <section className="ml-pipeline-rail" aria-label="ML lifecycle pipeline">
      <div className="ml-pipeline-mode" role="group" aria-label="Workflow mode">
        <button
          className={props.mode === "ANOMALY_DETECTION" ? "ml-pipeline-mode-active" : ""}
          onClick={() => props.onModeChange("ANOMALY_DETECTION")}
          type="button"
        >
          Anomaly detection
        </button>
        <button
          className={props.mode === "FAILURE_PREDICTION" ? "ml-pipeline-mode-active" : ""}
          onClick={() => props.onModeChange("FAILURE_PREDICTION")}
          type="button"
        >
          Failure prediction
        </button>
      </div>
      <details><summary>Pipeline prerequisites · {props.steps.filter((step) => step.status === "ready").length} / {props.steps.length} ready</summary><ol className="ml-pipeline-steps">
        {props.steps.map((step) => (
          <li className={`ml-pipeline-step ml-pipeline-step-${step.status}`} key={step.id}>
            <strong>{step.label}</strong>
            <span>{step.status}</span>
            <small>{step.detail}</small>
            <button type="button" className="ghost-button" onClick={() => props.onStep(step.id)}>{["queued", "running"].includes(step.status) ? "Review progress" : ["unavailable", "loading", "stale"].includes(step.status) ? "Verify" : step.status === "ready" ? "Review" : "Resolve prerequisite"} {step.label}</button>
          </li>
        ))}
      </ol></details>
    </section>
  );
}
