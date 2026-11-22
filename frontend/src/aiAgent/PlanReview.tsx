import { useEffect, useRef, useState, useSyncExternalStore } from "react";
import { Link } from "react-router-dom";
import { useActionConfirmation } from "../components/useActionConfirmation";
import { sessionIdentityRevision, subscribeSession } from "../api/client";
import { AgentPlan, ExecutePlanResponse, executeAgentPlan, getAgentPlan } from "./aiAgentApi";
import { redactAssistantDetails } from "./assistantContext";
import { DashboardCreationPreview } from "./DashboardCreationPreview";
import { PlanClarificationForm } from "./PlanClarificationForm";
import { DeviceSetupResult } from "./DeviceSetupResult";
import { NativeTableCreationPreview, nativeTableDefinition } from "./NativeTableCreationPreview";
import { SourceMappingPreview, sourceMappingExample } from "./SourceMappingPreview";
import { VehicleMonitoringPreview, monitoringReview } from "./VehicleMonitoringPreview";

export function planMutates(plan: AgentPlan) {
  return (
    plan.confirmationRequired ||
    plan.actions.some(
      (action) =>
        action.requiresConfirmation ||
        /create|update|delete|save|rotate|start|stop|disable|enable/i.test(action.tool),
    )
  );
}

export function PlanReview({
  plan,
  onOpen,
  onClarify,
  clarifying = false,
  onClarificationDirty,
  conversational = false,
  onBusyChange,
  onExecution,
}: {
  plan: AgentPlan;
  onOpen?: () => void;
  onClarify?: (answer: string) => Promise<boolean>;
  clarifying?: boolean;
  onClarificationDirty?: (dirty: boolean) => void;
  conversational?: boolean;
  onBusyChange?: (busy: boolean) => void;
  onExecution?: (execution: ExecutePlanResponse) => void;
}) {
  const confirmation = useActionConfirmation();
  const identity = useSyncExternalStore(
    subscribeSession,
    sessionIdentityRevision,
    sessionIdentityRevision,
  );
  const originalIdentity = useRef(identity);
  const fingerprint = JSON.stringify([
    plan.planId,
    plan.version,
    plan.payloadDigest,
    plan.task?.branchId,
    plan.task?.revision,
    plan.task?.status,
    identity,
  ]);
  const current = useRef(fingerprint);
  current.current = fingerprint;
  const [execution, setExecution] = useState<ExecutePlanResponse | null>(plan.execution ?? null);
  const [taskStatus, setTaskStatus] = useState(plan.executionStatus);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const pending = useRef(false);
  const mutating = planMutates(plan);
  const dashboardCreation =
    plan.actions.length === 1 &&
    ["CREATE_DASHBOARD", "UPDATE_DASHBOARD_DRAFT"].includes(plan.actions[0].tool);
  const nativeCreation =
    plan.actions.length === 1 && plan.actions[0].tool === "CREATE_NATIVE_TABLE";
  const monitoring =
    plan.actions.length === 1 && plan.actions[0].tool === "UPDATE_VEHICLE_MONITORING";
  const nativePreviewReady = plan.actions.every((action) => {
    if (action.tool === "UPDATE_VEHICLE_MONITORING") return monitoringReview(action) !== null;
    if (action.tool === "CREATE_NATIVE_TABLE") return nativeTableDefinition(action) !== null;
    if (action.tool === "APPLY_SOURCE_MAPPING") return sourceMappingExample(action) !== null;
    return true;
  });
  const needsInput =
    plan.planningStatus === "NEEDS_INPUT" ||
    plan.actions.length === 0 ||
    Boolean(plan.task && plan.task.status !== "READY");
  const expired =
    taskStatus === "EXPIRED" || Boolean(plan.expiresAt && Date.parse(plan.expiresAt) <= Date.now());
  const recorded =
    (execution?.planId === plan.planId && execution.status !== "READY") ||
    ["RUNNING", "UNKNOWN", "SUCCESS", "FAILED", "PARTIAL_SUCCESS"].includes(taskStatus ?? "");
  const canReconcile =
    (execution?.status ?? taskStatus) === "UNKNOWN" &&
    plan.actions.length === 1 &&
    ([
      "UPDATE_VEHICLE_MONITORING",
      "CREATE_DEVICE",
      "CREATE_NATIVE_TABLE",
      "CREATE_DASHBOARD",
      "UPDATE_DASHBOARD_DRAFT",
      "APPLY_SOURCE_MAPPING",
    ].includes(plan.actions[0].tool) ||
      (plan.actions[0].tool === "CREATE_DATA_SOURCE" &&
        plan.actions[0].parameters.provider === "HSL_HFP"));

  useEffect(() => {
    setExecution(plan.execution ?? null);
    setTaskStatus(plan.executionStatus);
    setError(null);
  }, [fingerprint]);
  useEffect(() => {
    current.current = fingerprint;
    return () => {
      current.current = "unmounted";
    };
  }, [fingerprint]);
  useEffect(() => {
    onBusyChange?.(busy);
    return () => onBusyChange?.(false);
  }, [busy, onBusyChange]);

  async function execute(reconcile = false) {
    if (
      pending.current ||
      busy ||
      clarifying ||
      !nativePreviewReady ||
      (recorded && !(reconcile && canReconcile)) ||
      needsInput ||
      (expired && !(reconcile && canReconcile && (nativeCreation || monitoring)))
    )
      return;
    const reviewed = current.current;
    pending.current = true;
    try {
      if (
        mutating &&
        !(reconcile && (nativeCreation || monitoring) && expired) &&
        !(conversational && dashboardCreation) &&
        (await confirmation.confirm({
          title: "Confirm and execute plan",
          object: plan.summary,
          impact: plan.actions
            .map((action, index) => `${index + 1}. ${action.title}: ${action.description}`)
            .join("\n"),
          dependencies:
            "Only the reviewed version is executed. Current domain access is checked again.",
        })) === null
      )
        return;
      if (current.current !== reviewed || originalIdentity.current !== sessionIdentityRevision())
        return;
      setBusy(true);
      setError(null);
      const result = await executeAgentPlan(
        plan.planId,
        mutating,
        plan.version,
        plan.payloadDigest,
      );
      if (current.current === reviewed) {
        setExecution(result);
        onExecution?.(result);
      }
    } catch (failure) {
      if (current.current === reviewed)
        setError(failure instanceof Error ? failure.message : "Plan execution failed");
    } finally {
      pending.current = false;
      if (current.current === reviewed) setBusy(false);
    }
  }

  async function refreshStatus() {
    if (pending.current) return;
    const reviewed = current.current;
    pending.current = true;
    setBusy(true);
    setError(null);
    try {
      const status = await getAgentPlan(plan.planId);
      if (current.current === reviewed) {
        setTaskStatus(status.executionStatus);
        setExecution(status.execution ?? null);
        if (status.execution) onExecution?.(status.execution);
      }
    } catch (failure) {
      if (current.current === reviewed)
        setError(failure instanceof Error ? failure.message : "Task status unavailable");
    } finally {
      pending.current = false;
      if (current.current === reviewed) setBusy(false);
    }
  }

  if (identity !== originalIdentity.current)
    return <p role="alert">Session changed. Prepare the task again.</p>;
  return (
    <section className={`panel form-stack${conversational ? " assistant-review" : ""}`}>
      {confirmation.dialog}
      <h2>{plan.summary}</h2>
      {!needsInput && !conversational ? <p>{plan.explanation}</p> : null}
      {needsInput ? (
        <div role="status">
          {(plan.task && plan.task.status !== "READY" && plan.actions.length
            ? [
                "Показан последний подготовленный вариант. Незавершённые изменения ещё не выполнены.",
              ]
            : plan.questions?.length
              ? plan.questions
              : [plan.explanation || "Уточните действие и необходимые параметры."]
          )
            .filter((question) => !conversational || question !== plan.explanation)
            .map((question) => (
              <p key={question}>{question}</p>
            ))}
        </div>
      ) : !conversational ? (
        <p>
          {mutating
            ? "This plan changes objects. Review each step before execution."
            : "Read-only plan."}
        </p>
      ) : null}
      {expired ? (
        <p role="status">
          {nativeCreation && canReconcile
            ? "Review expired. Inspect the saved receipt to recover the existing outcome."
            : "Plan expired. Prepare and review a new task."}
        </p>
      ) : null}
      {plan.actions.map((action, index) => (
        <article className="registry-row" key={action.id}>
          <div>
            {!(conversational && (dashboardCreation || nativeCreation)) ? (
              <strong>
                {index + 1}. {action.title}
              </strong>
            ) : null}
            {["CREATE_DASHBOARD", "UPDATE_DASHBOARD_DRAFT"].includes(action.tool) ? (
              <DashboardCreationPreview action={action} compact={conversational} />
            ) : action.tool === "CREATE_NATIVE_TABLE" ? (
              <NativeTableCreationPreview action={action} />
            ) : (
              <p>{action.description}</p>
            )}
            {action.tool === "UPDATE_VEHICLE_MONITORING" ? (
              <VehicleMonitoringPreview action={action} />
            ) : null}
            {action.tool === "APPLY_SOURCE_MAPPING" ? (
              <SourceMappingPreview action={action} />
            ) : null}
            {!conversational ? (
              <small>
                {action.requiresConfirmation ? "Changes require review" : "Read-only action"}
              </small>
            ) : null}
            {!conversational ? (
              <details>
                <summary>Technical parameters</summary>
                <pre>{JSON.stringify(redactAssistantDetails(action.parameters), null, 2)}</pre>
              </details>
            ) : null}
          </div>
        </article>
      ))}
      {onClarify && plan.planningStatus !== "FAILED" && !recorded && !expired ? (
        <PlanClarificationForm
          required={needsInput}
          busy={busy || clarifying}
          onSubmit={onClarify}
          onDirtyChange={onClarificationDirty}
        />
      ) : null}
      {!(conversational && (needsInput || recorded)) ? (
        <button
          className="primary-button"
          type="button"
          disabled={busy || clarifying || !nativePreviewReady || recorded || needsInput || expired}
          onClick={() => void execute()}
        >
          {busy
            ? "Executing..."
            : recorded
              ? "Execution recorded"
              : needsInput
                ? "Clarification required"
                : conversational && nativeCreation
                  ? "Создать эту таблицу"
                  : conversational && dashboardCreation
                    ? plan.actions[0].tool === "UPDATE_DASHBOARD_DRAFT"
                      ? "Обновить этот дашборд"
                      : "Создать этот дашборд"
                    : mutating
                      ? "Review and execute"
                      : "Run read-only"}
        </button>
      ) : null}
      {plan.version !== undefined && (!conversational || recorded) ? (
        <button type="button" disabled={busy || clarifying} onClick={() => void refreshStatus()}>
          {conversational ? "Проверить состояние задачи" : "Check task status"}
        </button>
      ) : null}
      {canReconcile ? (
        <button
          type="button"
          disabled={busy || !nativePreviewReady || (expired && !nativeCreation && !monitoring)}
          onClick={() => void execute(true)}
        >
          {plan.actions[0].tool === "APPLY_SOURCE_MAPPING"
            ? "Проверить квитанцию маппинга"
            : monitoring
              ? "Inspect monitoring receipt"
              : expired && nativeCreation
                ? "Inspect saved table receipt"
                : "Reconcile creation"}
        </button>
      ) : null}
      {error ? (
        <p className="error-message" role="alert">
          {error}
        </p>
      ) : null}
      {execution ? (
        <section aria-label="Plan execution">
          <h3>Result: {execution.status}</h3>
          {execution.status === "UNKNOWN" ? (
            <p>The response is uncertain. Check task status before any further action.</p>
          ) : null}
          {execution.actions.map((action) => (
            <article className="registry-row" key={action.actionId}>
              <div>
                <strong>
                  {plan.actions.find((step) => step.id === action.actionId)?.title ?? action.tool} ·{" "}
                  {action.status}
                </strong>
                {typeof action.result?.summary === "string" && !conversational ? (
                  <p className="assistant-result-text">{action.result.summary}</p>
                ) : null}
                {action.error ? <p>{action.error}</p> : null}
                {action.result?.setupStatus === "SOURCE_NOT_CONNECTED" ? (
                  <p>Устройство создано. Источник данных ещё не подключён.</p>
                ) : null}
                {typeof action.result?.deviceId === "string" &&
                ["WAITING_FOR_DATA", "TELEMETRY_READY"].includes(
                  String(action.result?.setupStatus),
                ) ? (
                  <DeviceSetupResult
                    key={action.result.deviceId}
                    deviceId={action.result.deviceId}
                    initialStatus={String(action.result.setupStatus)}
                  />
                ) : null}
                {action.result?.setupStatus === "PRIVATE_DRAFT" ? (
                  <p>
                    Личный черновик сохранён. Проверьте данные в обычном редакторе перед
                    публикацией.
                  </p>
                ) : null}
                {typeof action.result?.route === "string" &&
                action.result.route.startsWith("/") &&
                !action.result.route.startsWith("//") ? (
                  <Link to={action.result.route} onClick={onOpen}>
                    Open result
                  </Link>
                ) : null}
                {action.status === "SUCCESS" &&
                action.tool === "CREATE_DEVICE" &&
                typeof action.result?.deviceId === "string" &&
                /^[0-9a-f-]{36}$/i.test(action.result.deviceId) ? (
                  <p>
                    <Link
                      to={`/ai-agent?deviceId=${encodeURIComponent(action.result.deviceId)}&workflow=dashboard`}
                      onClick={onOpen}
                    >
                      Подготовить отдельный дашборд
                    </Link>
                  </p>
                ) : null}
              </div>
            </article>
          ))}
        </section>
      ) : null}
      <details>
        <summary>{conversational ? "Подробности задачи" : "Advanced diagnostics"}</summary>
        {plan.safetyNotes.map((note) => (
          <p key={note}>{note}</p>
        ))}
        {conversational
          ? plan.actions.map((action) => (
              <details key={action.id}>
                <summary>{action.title}</summary>
                <pre>{JSON.stringify(redactAssistantDetails(action.parameters), null, 2)}</pre>
              </details>
            ))
          : null}
        <p>
          Provider: {plan.providerName ?? plan.providerType ?? "Unavailable"}. Fallback:{" "}
          {plan.fallbackUsed === undefined ? "Unknown" : plan.fallbackUsed ? "Used" : "Not used"}.
        </p>
        {plan.version !== undefined ? (
          <p>
            Version {plan.version}. Expires{" "}
            {plan.expiresAt ? new Date(plan.expiresAt).toLocaleString() : "Unknown"}.
          </p>
        ) : null}
      </details>
    </section>
  );
}
