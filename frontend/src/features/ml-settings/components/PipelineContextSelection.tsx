import type { PipelineContext, PipelineEvidence } from "../model/pipelineContext";

export function PipelineContextSelection({ context, evidence, onChange }: { context: PipelineContext; evidence: PipelineEvidence; onChange: (value: Partial<PipelineContext>) => void }) {
  return <section className="panel"><h2>Selected pipeline</h2><p>Readiness applies to this lineage and loaded account/device scope. Review the dataset and snapshot defaults; choose a run, model and policy.</p>
    <div className="ml-control-grid">
      <label>Pipeline dataset<select value={context.datasetVersionId} onChange={(event) => onChange({ datasetVersionId: event.target.value, snapshotId: "", runId: "", modelId: "", policyId: "" })}><option value="">Select dataset version</option>{evidence.datasets.map((item) => <option key={item.datasetVersionId} value={item.datasetVersionId}>{item.name} · v{item.version} · {item.status}</option>)}</select></label>
      {context.family === "FAILURE_PREDICTION" ? <label>Pipeline horizon (days)<input type="number" min={1} value={context.horizonDays ?? ""} onChange={(event) => onChange({ horizonDays: event.target.value ? Number(event.target.value) : null })} /></label> : null}
      <label>Pipeline snapshot<select value={context.snapshotId} onChange={(event) => onChange({ snapshotId: event.target.value, runId: "", modelId: "", policyId: "" })}><option value="">Select feature snapshot</option>{evidence.snapshots.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.modelFamily ?? "Unknown family"} · {item.horizonDays ?? "no"} days</option>)}</select></label>
      <label>Pipeline training run<select value={context.runId} onChange={(event) => onChange({ runId: event.target.value, modelId: "", policyId: "" })}><option value="">Select training run</option>{evidence.runs.map((item) => <option key={item.id} value={item.id}>{item.id} · {item.modelFamily} · {item.status}</option>)}</select></label>
      <label>Pipeline model<select value={context.modelId} onChange={(event) => onChange({ modelId: event.target.value, policyId: "" })}><option value="">Select model</option>{evidence.models.map((item) => <option key={item.id} value={item.id}>{item.modelKey} · v{item.version} · {item.status}</option>)}</select></label>
      <label>Pipeline policy<select value={context.policyId} onChange={(event) => onChange({ policyId: event.target.value })}><option value="">Select deployment policy</option>{evidence.policies.map((item) => <option key={item.id} value={item.id}>{item.scope} · {item.mode} · {item.id}</option>)}</select></label>
    </div><p>Scope: {context.deviceId ? `device ${context.deviceId}` : "loaded account"}. {context.accountId ? "Account loaded." : "Load account/device settings to verify ownership."}</p>
  </section>;
}
