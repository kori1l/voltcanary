import { useEffect, useState } from "react";
import {
  getNotificationChannelDiagnostics,
  NotificationAttemptResponse,
  NotificationChannelDiagnosticsResponse,
  NotificationChannelResponse,
  testNotificationChannel,
} from "../../alerts/alertsApi";
import { CommandFeedback } from "../../components/CommandFeedback";
import { useEntityCommands } from "../../components/useEntityCommands";
import { sanitizeApiMessage } from "../../api/client";
import { formatEnum } from "./notificationChannelForm";

interface NotificationChannelOperationsProps {
  channel: NotificationChannelResponse;
  disabled?: boolean;
}

export function NotificationChannelOperations({ channel, disabled = false }: NotificationChannelOperationsProps) {
  const [diagnostics, setDiagnostics] = useState<NotificationChannelDiagnosticsResponse | null>(null);
  const [attempt, setAttempt] = useState<NotificationAttemptResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const commands = useEntityCommands();
  const isTesting = commands.isBlocked(`test:${channel.id}`);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    void loadDiagnostics();
  }, [channel.id]);

  async function loadDiagnostics() {
    setIsLoading(true);
    setError(null);
    try {
      setDiagnostics(await getNotificationChannelDiagnostics(channel.id));
    } catch (requestError) {
      setError(requestError instanceof Error ? requestError.message : "Failed to load channel diagnostics");
    } finally {
      setIsLoading(false);
    }
  }

  async function runTestDelivery() {
    await commands.run(`test:${channel.id}`, { label: `Delivery test ${channel.name}`, nonIdempotent: true,
      mutate: () => testNotificationChannel(channel.id, { sample: { title: `${channel.name} delivery check`, description: "Operator-triggered notification delivery test.", alertType: "NOTIFICATION_TEST", severity: "INFO" } }),
      onCommitted: setAttempt,
      refresh: async () => { setDiagnostics(await getNotificationChannelDiagnostics(channel.id)); },
    });
  }

  const health = diagnostics ? healthLabel(diagnostics) : isLoading ? "Loading diagnostics" : "Diagnostics unavailable";
  const currentAttemptStatus = attempt?.status ?? diagnostics?.lastTestAttemptStatus ?? null;

  return (
    <div className="notification-channel-operations">
      <CommandFeedback outcomes={commands.outcomes} onRetryRead={commands.retryRead} />
      {commands.outcomes[`test:${channel.id}`]?.phase === "unknown-result" && diagnostics?.lastTestAttemptId ? <div className="status-note"><p>Review the recorded test attempt {diagnostics.lastTestAttemptId} · {diagnostics.lastTestAttemptStatus}. This does not establish delivery success.</p><button className="ghost-button" type="button" onClick={() => commands.reconcile(`test:${channel.id}`, { id: diagnostics.lastTestAttemptId, status: diagnostics.lastTestAttemptStatus })}>Confirm reviewed test attempt</button></div> : null}
      <div className="notification-health-strip">
        <span className={`status-pill status-${healthTone(diagnostics)}`}>{health}</span>
        <span>{!diagnostics ? "Adapter status unknown" : diagnostics.adapterRegistered === false ? "Adapter unavailable" : "Adapter ready"}</span>
        <span>
          Queue {diagnostics ? diagnostics.pendingCount + diagnostics.retryingCount : "unknown"} / Failed{" "}
          {diagnostics?.failedCount ?? "unknown"}
        </span>
        <span>{lastActivity(diagnostics)}</span>
      </div>
      {currentAttemptStatus ? (
        <div className="notification-test-result">
          <strong>Last test: {formatEnum(currentAttemptStatus)}</strong>
          {attempt?.retryExhausted || diagnostics?.lastTestRetryExhausted ? <span>Retries exhausted</span> : null}
          {attempt?.nextAttemptAt && attempt.status === "RETRYING" ? <span>Next retry {formatDate(attempt.nextAttemptAt)}</span> : null}
          {attempt?.lastErrorCode ? <span>{sanitizeApiMessage(attempt.lastErrorCode)}</span> : null}
          {attempt?.providerResponseSummary ? <span>{sanitizeApiMessage(attempt.providerResponseSummary)}</span> : null}
        </div>
      ) : null}
      {error ? <div className="error-message account-message">{error}</div> : null}
      <div className="action-row">
        <button className="ghost-button" type="button" onClick={() => void loadDiagnostics()} disabled={disabled || isLoading || isTesting}>
          Refresh health
        </button>
        <button className="ghost-button" type="button" onClick={() => void runTestDelivery()} disabled={disabled || isTesting}>
          {isTesting ? "Testing..." : "Send test"}
        </button>
      </div>
    </div>
  );
}

function healthLabel(diagnostics: NotificationChannelDiagnosticsResponse) {
  if (!diagnostics.enabled) {
    return "Disabled";
  }
  if (!diagnostics.adapterRegistered) {
    return "Unsupported";
  }
  if (diagnostics.retryingCount > 0) {
    return "Retrying";
  }
  if (diagnostics.failedCount > 0 && !diagnostics.lastSuccessfulDeliveryAt) {
    return "Failing";
  }
  return "Ready";
}

function healthTone(diagnostics: NotificationChannelDiagnosticsResponse | null) {
  if (!diagnostics) {
    return "neutral";
  }
  if (!diagnostics.enabled || !diagnostics.adapterRegistered || diagnostics.failedCount > 0) {
    return "warning";
  }
  if (diagnostics.retryingCount > 0 || diagnostics.pendingCount > 0) {
    return "pending";
  }
  return "success";
}

function lastActivity(diagnostics: NotificationChannelDiagnosticsResponse | null) {
  if (!diagnostics) {
    return "No activity";
  }
  if (diagnostics.lastFailureAt) {
    return `Last failure ${formatDate(diagnostics.lastFailureAt)}`;
  }
  if (diagnostics.lastSuccessfulDeliveryAt) {
    return `Last success ${formatDate(diagnostics.lastSuccessfulDeliveryAt)}`;
  }
  return "No deliveries yet";
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, {
    month: "short",
    day: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  }).format(new Date(value));
}
