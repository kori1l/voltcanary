import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import {
  getNotificationChannelDiagnostics,
  NotificationChannelDiagnosticsResponse,
  NotificationChannelResponse,
  testNotificationChannel,
} from "../../alerts/alertsApi";
import { NotificationChannelOperations } from "./NotificationChannelOperations";

vi.mock("../../alerts/alertsApi", async () => {
  const actual = await vi.importActual<typeof import("../../alerts/alertsApi")>("../../alerts/alertsApi");
  return {
    ...actual,
    getNotificationChannelDiagnostics: vi.fn(),
    testNotificationChannel: vi.fn(),
  };
});

describe("NotificationChannelOperations", () => {
  it("renders unsupported failure diagnostics without secrets", async () => {
    vi.mocked(getNotificationChannelDiagnostics).mockResolvedValueOnce({
      ...diagnostics(),
      adapterRegistered: false,
      failedCount: 1,
      lastFailureAt: "2026-09-08T10:20:00Z",
      lastFailureErrorCode: "HTTP_401",
      lastFailureSummary: "provider rejected token=[redacted]",
      lastTestAttemptStatus: "FAILED",
      lastTestRetryExhausted: true,
    });

    render(<NotificationChannelOperations channel={channel()} />);

    expect(await screen.findByText("Unsupported")).toBeInTheDocument();
    expect(screen.getByText("Adapter unavailable")).toBeInTheDocument();
    expect(screen.getByText("Last test: Failed")).toBeInTheDocument();
    expect(screen.getByText("Retries exhausted")).toBeInTheDocument();
  });

  it("runs a test send and renders retry state", async () => {
    vi.mocked(getNotificationChannelDiagnostics)
      .mockResolvedValueOnce(diagnostics())
      .mockResolvedValueOnce({
        ...diagnostics(),
        retryingCount: 1,
        lastTestAttemptStatus: "RETRYING",
        lastTestNextAttemptAt: "2026-09-08T10:21:00Z",
      });
    vi.mocked(testNotificationChannel).mockResolvedValueOnce({
      id: "attempt-1",
      alertId: null,
      channelId: "channel-1",
      accountId: "account-1",
      channelType: "WEBHOOK",
      deliveryEventType: "NOTIFICATION_TEST",
      status: "RETRYING",
      retryCount: 1,
      maxAttempts: 3,
      nextAttemptAt: "2026-09-08T10:21:00Z",
      retryExhausted: false,
      lastErrorCode: "HTTP_503",
      providerResponseSummary: "Provider unavailable",
      providerMessageId: null,
      createdAt: "2026-09-08T10:20:00Z",
      updatedAt: "2026-09-08T10:20:01Z",
      sentAt: null,
    });

    render(<NotificationChannelOperations channel={channel()} />);

    await userEvent.click(await screen.findByRole("button", { name: /send test/i }));

    await waitFor(() => expect(testNotificationChannel).toHaveBeenCalledWith("channel-1", expect.any(Object)));
    expect(screen.getByText("Last test: Retrying")).toBeInTheDocument();
    expect(screen.getByText("HTTP_503")).toBeInTheDocument();
    expect(screen.getByText("Provider unavailable")).toBeInTheDocument();
  });
});

function channel(): NotificationChannelResponse {
  return {
    id: "channel-1",
    accountId: "account-1",
    name: "Ops webhook",
    channelType: "WEBHOOK",
    config: { url: "https://hooks.example.test/alerts" },
    credentialsRef: null,
    enabled: true,
    createdAt: "2026-09-08T10:00:00Z",
    updatedAt: "2026-09-08T10:00:00Z",
  };
}

function diagnostics(): NotificationChannelDiagnosticsResponse {
  return {
    channelId: "channel-1",
    accountId: "account-1",
    channelType: "WEBHOOK",
    enabled: true,
    adapterRegistered: true,
    lastSuccessfulDeliveryAt: null,
    lastFailureAt: null,
    lastFailureErrorCode: null,
    lastFailureSummary: null,
    retryingCount: 0,
    failedCount: 0,
    pendingCount: 0,
    oldestPendingOrRetryingAgeSeconds: null,
    lastTestAttemptId: null,
    lastTestAttemptStatus: null,
    lastTestRetryCount: 0,
    lastTestMaxAttempts: 0,
    lastTestNextAttemptAt: null,
    lastTestRetryExhausted: false,
  };
}
