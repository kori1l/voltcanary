import { FormEvent, useEffect, useRef, useState } from "react";
import {
  MlDeploymentMode,
  MlDeploymentPolicy,
  MlDeploymentScope,
  MlLifecycleStatusFilter,
  MlModelVersion,
  resolveDeploymentPolicy,
  saveDeploymentPolicy,
} from "../../../analytics/mlModuleApi";
import { safeMlError } from "../../../analytics/mlSettingsApi";
import { Device, DeviceType } from "../../../devices/devicesApi";
import { useActionConfirmation } from "../../../components/useActionConfirmation";
import { useEntityCommands } from "../../../components/useEntityCommands";
import { CommandFeedback } from "../../../components/CommandFeedback";
import { markWorkflowDraftSaved } from "../../../components/workflowDraftContext";
import { StatusFilterSelect, modelOriginLabel } from "./mlOperationalPresentation";

export function MlPoliciesTab(props: {
  selectedModelId?: string;
  selectedDeviceId?: string | null;
  onModelSelected?: (modelId: string) => void;
  onPolicySelected?: (policyId: string) => void;
  devices: Device[];
  deviceTypes: DeviceType[];
  models: MlModelVersion[];
  policies: MlDeploymentPolicy[];
  policiesAvailable?: boolean;
  statusFilter: MlLifecycleStatusFilter;
  onChanged: () => Promise<void>;
  onConfirmed?: () => void;
  onFilterChange: (value: MlLifecycleStatusFilter) => Promise<void>;
  isLifecyclePending?: (id: string, action: string) => boolean;
  onLifecycle: (policy: MlDeploymentPolicy, action: "disable" | "delete") => Promise<void>;
  setError: (value: string | null) => void;
}) {
  const deployableModels = props.models.filter(
    (model) => (model.status === "APPROVED" || model.status === "ACTIVE") && !model.demoModel,
  );
  const [scope, setScope] = useState<MlDeploymentScope>("ACCOUNT");
  const [mode, setMode] = useState<MlDeploymentMode>("SHADOW");
  const [modelVersionId, setModelVersionId] = useState("");
  const [deviceId, setDeviceId] = useState("");
  const [deviceType, setDeviceType] = useState("");
  const [canaryPercent, setCanaryPercent] = useState(10);
  const [fallbackEnabled, setFallbackEnabled] = useState(true);
  const [resolved, setResolved] = useState<string | null>(null);
  const confirmation = useActionConfirmation();
  const submittedPolicy = useRef<{
    scope: string;
    deviceId: string;
    deviceType: string;
    modelVersionId: string;
    mode: string;
  } | null>(null);
  const commands = useEntityCommands();
  const saving = commands.isBlocked("policy:save");
  const validPolicy =
    (mode === "DISABLED" || deployableModels.some((model) => model.id === modelVersionId)) &&
    (scope !== "DEVICE" || Boolean(deviceId)) &&
    (scope !== "DEVICE_TYPE" || Boolean(deviceType)) &&
    (mode !== "CANARY" ||
      (Number.isFinite(canaryPercent) && canaryPercent >= 0 && canaryPercent <= 100));

  useEffect(() => {
    if (props.selectedModelId && props.selectedModelId !== modelVersionId) {
      setModelVersionId(props.selectedModelId);
      return;
    }
    if (!modelVersionId && deployableModels[0]) {
      setModelVersionId(deployableModels[0].id);
      props.onModelSelected?.(deployableModels[0].id);
    }
  }, [deployableModels, modelVersionId, props.selectedModelId]);
  useEffect(() => {
    if (props.selectedDeviceId !== undefined) {
      setDeviceId(props.selectedDeviceId ?? "");
      setScope(props.selectedDeviceId ? "DEVICE" : "ACCOUNT");
    }
  }, [props.selectedDeviceId]);

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!validPolicy || saving) return;
    submittedPolicy.current = {
      scope,
      deviceId: scope === "DEVICE" ? deviceId : "",
      deviceType: scope === "DEVICE_TYPE" ? deviceType : "",
      modelVersionId: mode === "DISABLED" ? "" : modelVersionId,
      mode,
    };
    let reason = "";
    await commands.run("policy:save", {
      label: "Apply deployment policy",
      nonIdempotent: true,
      review: async () => {
        const answer = await confirmation.confirm({
          title: "Apply deployment policy",
          object: `${scope === "DEVICE" ? (props.devices.find((device) => device.id === deviceId)?.name ?? deviceId) : scope === "DEVICE_TYPE" ? deviceType : "Account defaults"} · ${mode}`,
          impact: `${mode === "SHADOW" ? "Evaluates the model separately from the primary serving path." : mode === "CANARY" ? `Uses the model for ${canaryPercent}% of the configured canary cohort.` : mode === "ACTIVE" ? "Uses this approved model for eligible predictions." : "Disables the selected deployment scope."} Model: ${mode === "DISABLED" ? "none" : (deployableModels.find((model) => model.id === modelVersionId)?.modelKey ?? modelVersionId)}. Baseline fallback ${fallbackEnabled ? "enabled" : "disabled"}. More specific device policies take precedence over type and account settings.`,
          reasonRequired: true,
        });
        reason = answer ?? "";
        return answer !== null;
      },
      mutate: () =>
        saveDeploymentPolicy({
          scope,
          deviceId: scope === "DEVICE" ? deviceId || null : null,
          deviceType: scope === "DEVICE_TYPE" ? deviceType || null : null,
          modelVersionId: mode === "DISABLED" ? null : modelVersionId || null,
          mode,
          canaryPercent: mode === "CANARY" ? canaryPercent : null,
          fallbackEnabled,
          reason,
        }),
      onCommitted: (savedPolicy) => {
        props.onConfirmed?.();
        props.onPolicySelected?.(savedPolicy.id);
        markWorkflowDraftSaved("ml-policy");
      },
      refresh: props.onChanged,
    });
  }

  async function resolve() {
    try {
      const policy = await resolveDeploymentPolicy(deviceId, deviceType);
      setResolved(
        `${policy.policySource ?? "BASELINE"} / ${policy.mode} / ${policy.fallbackReason ?? "model selected"}`,
      );
    } catch (error) {
      props.setError(safeMlError(error));
    }
  }

  return (
    <div className="ml-workflow-grid">
      {confirmation.dialog}
      <CommandFeedback outcomes={commands.outcomes} onRetryRead={commands.retryRead} />
      {commands.outcomes["policy:save"]?.phase === "unknown-result" ? (
        <div>
          <p>Review deployment policies before submitting again.</p>
          {props.policies
            .filter(
              (policy) =>
                submittedPolicy.current &&
                policy.scope === submittedPolicy.current.scope &&
                policy.mode === submittedPolicy.current.mode &&
                (policy.deviceId ?? "") === submittedPolicy.current.deviceId &&
                (policy.deviceType ?? "") === submittedPolicy.current.deviceType &&
                (policy.modelVersionId ?? "") === submittedPolicy.current.modelVersionId,
            )
            .map((policy) => (
              <button
                type="button"
                key={policy.id}
                onClick={() => {
                  commands.reconcile("policy:save", policy);
                  props.onPolicySelected?.(policy.id);
                }}
              >
                Use matching policy {policy.id} · {policy.mode}
              </button>
            ))}
        </div>
      ) : null}
      <form className="panel ml-workflow-form" data-draft-id="ml-policy" onSubmit={submit}>
        <h2>Deployment policy editor</h2>
        <div className="ml-control-grid">
          <label>
            Scope
            <select value={scope} onChange={(e) => setScope(e.target.value as MlDeploymentScope)}>
              <option value="ACCOUNT">Account</option>
              <option value="DEVICE_TYPE">Device type</option>
              <option value="DEVICE">Device</option>
            </select>
          </label>
          <label>
            Mode
            <select value={mode} onChange={(e) => setMode(e.target.value as MlDeploymentMode)}>
              <option value="SHADOW">Shadow</option>
              <option value="CANARY">Canary</option>
              <option value="ACTIVE">Active</option>
              <option value="DISABLED">Disabled</option>
            </select>
          </label>
          <label>
            Model
            <select
              value={modelVersionId}
              onChange={(e) => {
                setModelVersionId(e.target.value);
                props.onModelSelected?.(e.target.value);
              }}
            >
              <option value="">No model</option>
              {deployableModels.map((model) => (
                <option key={model.id} value={model.id}>
                  {model.modelKey} v{model.version} / {modelOriginLabel(model)}
                </option>
              ))}
            </select>
          </label>
          {scope === "DEVICE" && (
            <label>
              Device
              <select value={deviceId} onChange={(e) => setDeviceId(e.target.value)}>
                <option value="">Select device</option>
                {props.devices.map((device) => (
                  <option key={device.id} value={device.id}>
                    {deviceLabel(device)}
                  </option>
                ))}
              </select>
            </label>
          )}
          {scope === "DEVICE_TYPE" && (
            <label>
              Device type
              <select value={deviceType} onChange={(e) => setDeviceType(e.target.value)}>
                <option value="">Select device type</option>
                {props.deviceTypes.map((type) => (
                  <option key={type.code} value={type.code}>
                    {type.displayName} / {type.code}
                  </option>
                ))}
              </select>
            </label>
          )}
          {mode === "CANARY" && (
            <label>
              Canary percent
              <input
                max="100"
                min="0"
                onChange={(e) => setCanaryPercent(Number(e.target.value))}
                type="number"
                value={canaryPercent}
              />
            </label>
          )}
        </div>
        <label className="checkbox-label">
          <input
            checked={fallbackEnabled}
            onChange={(e) => setFallbackEnabled(e.target.checked)}
            type="checkbox"
          />{" "}
          Baseline fallback enabled
        </label>
        <p className="status-note">
          Shadow records comparison evidence; Canary serves a percentage; Active serves the approved
          model. Fallback is a configured alternative, not a measured success rate. Select a device
          and resolve its effective policy to inspect inheritance before applying changes.
        </p>
        <div className="action-row action-row-start">
          <button className="primary-button" disabled={!validPolicy || saving}>
            Save policy
          </button>
          <button className="ghost-button" onClick={() => void resolve()} type="button">
            Resolve policy
          </button>
        </div>
        {resolved && <div className="success-message">{resolved}</div>}
      </form>
      <section className="panel">
        <div className="section-header">
          <div>
            <h2>Current policies</h2>
          </div>
          <StatusFilterSelect
            label="Policy status filter"
            value={props.statusFilter}
            onChange={props.onFilterChange}
          />
        </div>
        {props.policiesAvailable === false ? (
          <p>Policy records unavailable or loading.</p>
        ) : props.policies.length === 0 ? (
          <div className="empty-state">No deployment policies yet.</div>
        ) : (
          props.policies.map((policy) => (
            <div className="ml-readout" key={policy.id}>
              <strong>
                {policy.scope} / {policy.mode}
              </strong>
              <span>
                {policy.modelVersionId ?? "baseline fallback"} / fallback{" "}
                {policy.fallbackEnabled ? "on" : "off"}
              </span>
              <div className="action-row action-row-start">
                {policy.mode !== "DISABLED" && (
                  <button
                    aria-label={`Disable policy ${policy.scope} ${policy.id}`}
                    className="ghost-button"
                    disabled={props.isLifecyclePending?.(policy.id, "disable")}
                    onClick={() => void props.onLifecycle(policy, "disable")}
                    type="button"
                  >
                    Disable
                  </button>
                )}
                {policy.mode === "DISABLED" && (
                  <button
                    aria-label={`Delete policy ${policy.scope} ${policy.id}`}
                    className="ghost-button"
                    disabled={props.isLifecyclePending?.(policy.id, "delete")}
                    onClick={() => void props.onLifecycle(policy, "delete")}
                    type="button"
                  >
                    Delete
                  </button>
                )}
              </div>
            </div>
          ))
        )}
      </section>
    </div>
  );
}

function deviceLabel(device: Device) {
  return `${device.name} / ${device.type} / ${device.status}`;
}
