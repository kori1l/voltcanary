import { useEffect, useState } from "react";
import { createInternalTelemetryDataset, MlDatasetDetail, MlDatasetSummary, MlFeatureSnapshotResponse, MlInternalDatasetResponse, MlLifecycleStatusFilter } from "../../../analytics/mlModuleApi";
import { Device } from "../../../devices/devicesApi";
import { DatasetWorkbench } from "../../../features/ml-datasets/components/DatasetWorkbench";
import { DatasetWorkbenchDefaults } from "../../../features/ml-datasets/model/datasetWorkbench.types";

import { WorkflowMode, FeatureSnapshotConfig, DatasetLifecycleKind, ArchiveDeleteAction, defaultFeatureFamilies, splitList, humanizeKey, formatQualityValue } from "../model/mlConsolePresentation";
import { StatusFilterSelect } from "./MlLifecycleFilter";
export function DatasetsTab(props: {
  accountId: string;
  datasets: MlDatasetSummary[];
  datasetsAvailable?: boolean;
  devices: Device[];
  detail: MlDatasetDetail | null;
  featureSnapshots: MlFeatureSnapshotResponse[];
  workflowMode: WorkflowMode;
  statusFilter: MlLifecycleStatusFilter;
  onCreateInternalDataset: (body: Parameters<typeof createInternalTelemetryDataset>[0]) => Promise<MlInternalDatasetResponse>;
  onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>;
  onGenerateFeatureSnapshot: (body: FeatureSnapshotConfig) => Promise<void>;
  onLifecycle: (kind: DatasetLifecycleKind, id: string, action: ArchiveDeleteAction, label: string) => Promise<void>;
  onSelect: (datasetVersionId: string) => Promise<void>;
  onWorkflowModeChange: (mode: WorkflowMode) => void;
}) {
  const [modelFamily, setModelFamily] = useState<WorkflowMode>(props.workflowMode);
  const [horizonDays, setHorizonDays] = useState(30);
  const [featureTemplateId, setFeatureTemplateId] = useState("generic-appliance");
  const [featureTemplateVersion, setFeatureTemplateVersion] = useState("template-v1");
  const [rollingWindowSize, setRollingWindowSize] = useState(6);
  const [missingGapSeconds, setMissingGapSeconds] = useState(21600);
  const [featureFamiliesText, setFeatureFamiliesText] = useState(defaultFeatureFamilies("ANOMALY_DETECTION").join("\n"));
  const selectedDatasetVersionId = props.detail?.dataset.datasetVersionId ?? "";
  const detailSnapshots = props.featureSnapshots.filter((snapshot) => snapshot.datasetVersionId === selectedDatasetVersionId);

  useEffect(() => {
    setModelFamily(props.workflowMode);
    setFeatureFamiliesText(defaultFeatureFamilies(props.workflowMode).join("\n"));
  }, [props.workflowMode]);

  function applyWorkbenchDefaults(defaults: DatasetWorkbenchDefaults) {
    setModelFamily(defaults.modelFamily);
    props.onWorkflowModeChange(defaults.modelFamily);
    setHorizonDays(defaults.horizonDays ?? 30);
    setFeatureFamiliesText(defaultFeatureFamilies(defaults.modelFamily).join("\n"));
  }

  function chooseModelFamily(nextModelFamily: WorkflowMode) {
    setModelFamily(nextModelFamily);
    props.onWorkflowModeChange(nextModelFamily);
    setFeatureFamiliesText(defaultFeatureFamilies(nextModelFamily).join("\n"));
  }

  function generateSnapshot() {
    if (!selectedDatasetVersionId) return;
    if (modelFamily === "FAILURE_PREDICTION" && (!horizonDays || horizonDays < 1)) {
      return;
    }
    void props.onGenerateFeatureSnapshot({
      datasetVersionId: selectedDatasetVersionId,
      featureSetVersion: "feature-set-v1",
      codeConfigVersion: "analytics-feature-pipeline-v1",
      rollingWindowSize,
      missingGapSeconds,
      enabledFeatureFamilies: splitList(featureFamiliesText),
      modelFamily,
      horizonDays: modelFamily === "FAILURE_PREDICTION" ? horizonDays : null,
      featureTemplateId,
      featureTemplateVersion,
    });
  }

  return (
    <div className="ml-datasets-workspace">
      <DatasetWorkbench
        accountId={props.accountId}
        devices={props.devices}
        workflowMode={props.workflowMode}
        onCreate={props.onCreateInternalDataset}
        onCreatedFromWorkbench={applyWorkbenchDefaults}
        onWorkflowModeChange={props.onWorkflowModeChange}
      />
      <div className="ml-datasets-secondary-grid">
        <section className="panel">
          <div className="section-header">
            <div>
              <h2>Dataset versions</h2>
              <p>Validation status, lineage, and feature preparation.</p>
            </div>
            <StatusFilterSelect label="Dataset status filter" value={props.statusFilter} onChange={props.onFilterChange} />
          </div>
          {props.datasetsAvailable === false ? <p>Dataset records unavailable or loading.</p> : props.datasets.length === 0 ? <div className="empty-state">No datasets have been created or imported yet.</div> : (
            <div className="ml-table">
              {props.datasets.map((dataset) => (
                <button className="ml-table-row" key={dataset.datasetVersionId} onClick={() => void props.onSelect(dataset.datasetVersionId)} type="button">
                  <strong>{dataset.name}</strong>
                  <span>{dataset.sourceType}</span>
                  <span>{dataset.status}</span>
                  <span>v{dataset.version}</span>
                </button>
              ))}
            </div>
          )}
        </section>
        <section className="panel ml-datasets-detail-panel">
        <div className="section-header">
          <div>
            <h2>Dataset detail</h2>
            <p>Validation reports and deterministic lineage.</p>
          </div>
        </div>
        {!props.detail ? <div className="empty-state">Select a dataset version to inspect validation and lineage.</div> : (
          <>
            <dl className="detail-list">
              <div><dt>Status</dt><dd>{props.detail.dataset.status}</dd></div>
              <div><dt>Version id</dt><dd className="mono-value">{props.detail.dataset.datasetVersionId}</dd></div>
              <div><dt>Split</dt><dd>{props.detail.dataset.splitStrategy}</dd></div>
              <div><dt>Feature config</dt><dd>{props.detail.dataset.featureConfigVersion}</dd></div>
              <div><dt>Artifact URI</dt><dd className="mono-value">{props.detail.dataset.artifactUri ?? "No external artifact"}</dd></div>
              <div><dt>Artifact hash</dt><dd className="mono-value">{props.detail.dataset.artifactHash ?? props.detail.dataset.dataHash ?? "Unavailable"}</dd></div>
              <div><dt>Immutable rows</dt><dd className="mono-value">{props.detail.dataset.immutableRowReference ?? "Unavailable"}</dd></div>
              <div><dt>Failure reason</dt><dd>{props.detail.dataset.failureReason ?? "None"}</dd></div>
            </dl>
            <div className="action-row action-row-start">
              <button className="ghost-button" onClick={() => void props.onLifecycle("dataset", props.detail!.dataset.datasetVersionId, "archive", props.detail!.dataset.name)} type="button">Archive dataset</button>
              {["DRAFT", "FAILED"].includes(props.detail.dataset.status) && (
                <button className="ghost-button" onClick={() => void props.onLifecycle("dataset", props.detail!.dataset.datasetVersionId, "delete", props.detail!.dataset.name)} type="button">Delete dataset</button>
              )}
            </div>
            <h3>Validation</h3>
            {props.detail.validationReports.map((report) => (
              <div className="ml-readout" key={report.id}>
                <strong>{report.summary}</strong>
                <span>{report.acceptedRows} accepted / {report.rejectedRows} rejected / {report.warningCount} warnings</span>
                {Object.keys(report.rejectedReasons ?? {}).length > 0 && (
                  <dl className="ml-quality-grid">
                    {Object.entries(report.rejectedReasons).map(([reason, count]) => (
                      <div key={reason}>
                        <dt>{humanizeKey(reason)}</dt>
                        <dd>{count} rejected rows</dd>
                      </div>
                    ))}
                  </dl>
                )}
                {Object.keys(report.qualityProfile ?? {}).length > 0 && (
                  <dl className="ml-quality-grid">
                    {Object.entries(report.qualityProfile).map(([key, value]) => (
                      <div key={key}>
                        <dt>{humanizeKey(key)}</dt>
                        <dd>{formatQualityValue(value)}</dd>
                      </div>
                    ))}
                  </dl>
                )}
              </div>
            ))}
            <h3>Lineage</h3>
            {props.detail.lineage.map((lineage) => (
              <div className="ml-readout" key={lineage.id}>
                <strong>{lineage.sourceReference}</strong>
                <span className="mono-value">{lineage.contentHash}</span>
              </div>
            ))}
            <section className="ml-inline-panel">
              <h3>Feature snapshot</h3>
              <div className="ml-form-grid">
                <label>Model family<select value={modelFamily} onChange={(event) => chooseModelFamily(event.target.value as typeof modelFamily)}><option value="ANOMALY_DETECTION">Anomaly detection</option><option value="FAILURE_PREDICTION">Failure prediction</option></select></label>
                <label>Feature template<select value={featureTemplateId} onChange={(event) => setFeatureTemplateId(event.target.value)}><option value="generic-appliance">Generic appliance</option><option value="fridge-freezer">Fridge/freezer</option></select></label>
                <label>Template version<input value={featureTemplateVersion} onChange={(event) => setFeatureTemplateVersion(event.target.value)} /></label>
                <label>Rolling window<input min="1" type="number" value={rollingWindowSize} onChange={(event) => setRollingWindowSize(Number(event.target.value))} /></label>
                <label>Max gap seconds<input min="1" type="number" value={missingGapSeconds} onChange={(event) => setMissingGapSeconds(Number(event.target.value))} /></label>
                {modelFamily === "FAILURE_PREDICTION" && <label>Snapshot horizon days<input min="1" max="365" type="number" value={horizonDays} onChange={(event) => setHorizonDays(Number(event.target.value))} required /></label>}
              </div>
              {modelFamily === "FAILURE_PREDICTION" && (
                <div className="ml-readout">
                  <strong>Failure feature snapshot</strong>
                  <span>Use the same horizon as generated labels and failure-prediction training.</span>
                </div>
              )}
              <label>Feature families<textarea className="ml-compact-editor" value={featureFamiliesText} onChange={(event) => setFeatureFamiliesText(event.target.value)} /></label>
              <div className="action-row action-row-start">
                <button className="primary-button" disabled={modelFamily === "FAILURE_PREDICTION" && (!horizonDays || horizonDays < 1)} onClick={generateSnapshot} type="button">Generate feature snapshot</button>
              </div>
              {detailSnapshots.length === 0 ? (
                <div className="empty-state">No feature snapshots generated for this dataset yet.</div>
              ) : detailSnapshots.map((snapshot) => (
                <div className="ml-readout" key={snapshot.id}>
                  <FeatureSnapshotSummary snapshot={snapshot} />
                  <div className="action-row action-row-start">
                    <button className="ghost-button" onClick={() => void props.onLifecycle("snapshot", snapshot.id, "archive", "feature snapshot")} type="button">Archive snapshot</button>
                    <button className="ghost-button" onClick={() => void props.onLifecycle("snapshot", snapshot.id, "delete", "feature snapshot")} type="button">Delete snapshot</button>
                  </div>
                </div>
              ))}
            </section>
          </>
        )}
      </section>
      </div>
    </div>
  );
}

function FeatureSnapshotSummary({ snapshot }: { snapshot: MlFeatureSnapshotResponse }) {
  return (
    <div className={snapshot.rejectedRowCount > 0 ? "warning-message" : "success-message"}>
      <dl className="detail-list">
        <div><dt>Snapshot id</dt><dd className="mono-value">{snapshot.id}</dd></div>
        <div><dt>Family</dt><dd>{snapshot.modelFamily ?? "ANOMALY_DETECTION"}</dd></div>
        <div><dt>Horizon</dt><dd>{snapshot.horizonDays ? `${snapshot.horizonDays} days` : "Not required"}</dd></div>
        <div><dt>Rows</dt><dd>{snapshot.rowCount} ready / {snapshot.rejectedRowCount} rejected</dd></div>
        <div><dt>Feature schema hash</dt><dd className="mono-value">{snapshot.featureSchemaHash}</dd></div>
        <div><dt>Feature data hash</dt><dd className="mono-value">{snapshot.featureDataHash ?? "Pending"}</dd></div>
        <div><dt>Feature config hash</dt><dd className="mono-value">{snapshot.featureConfigHash ?? "Pending"}</dd></div>
        <div><dt>Artifact URI</dt><dd className="mono-value">{snapshot.artifactUri}</dd></div>
        <div><dt>Immutable rows</dt><dd className="mono-value">{snapshot.immutableRowReference ?? "Unavailable"}</dd></div>
        <div><dt>Template</dt><dd>{snapshot.featureTemplateId ?? "generic-appliance"} / {snapshot.featureTemplateVersion ?? "template-v1"}</dd></div>
      </dl>
    </div>
  );
}

