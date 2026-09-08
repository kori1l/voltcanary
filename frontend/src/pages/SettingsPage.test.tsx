import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { SettingsPage } from "./SettingsPage";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("SettingsPage notification settings", () => {
  it("keeps channel and preference routes available from focused notification settings", async () => {
    const requests: { url: string; method: string; body?: unknown }[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const path = new URL(url).pathname;
      const method = init?.method ?? "GET";
      requests.push({
        url: path,
        method,
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
      });

      if (path === "/auth/me") {
        return json({
          id: "user-1",
          email: "ops@example.com",
          role: "ADMIN",
          status: "ACTIVE",
          emailVerified: true,
        });
      }
      if (path === "/alerts/notification-channels" && method === "GET") {
        return json([
          {
            id: "channel-1",
            accountId: "account-1",
            name: "Ops webhook",
            channelType: "WEBHOOK",
            config: { url: "https://hooks.example.test/alerts" },
            credentialsRef: null,
            enabled: true,
            createdAt: "2026-09-07T09:00:00Z",
            updatedAt: "2026-09-07T09:00:00Z",
          },
        ]);
      }
      if (path === "/alerts/notification-channels" && method === "POST") {
        const body = init?.body ? JSON.parse(String(init.body)) : {};
        return json({
          id: "channel-created",
          accountId: "account-1",
          createdAt: "2026-09-07T09:00:00Z",
          updatedAt: "2026-09-07T09:00:00Z",
          ...body,
        }, 201);
      }
      if (path === "/alerts/notification-channels/channel-1/diagnostics" && method === "GET") {
        return json(diagnostics());
      }
      if (path === "/alerts/notification-channels/channel-1/test" && method === "POST") {
        return json(testAttempt());
      }
      if (path === "/alerts/notification-preferences" && method === "GET") {
        return json(preference());
      }
      if (path === "/alerts/notification-preferences" && method === "PUT") {
        return json({ ...preference(), ...(init?.body ? JSON.parse(String(init.body)) : {}) });
      }
      return json({}, 404);
    }));

    render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>,
    );

    expect(await screen.findByText("Ops webhook")).toBeInTheDocument();
    expect(await screen.findByText("Adapter ready")).toBeInTheDocument();
    expect(await screen.findByText("Notification Preferences")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /send test/i }));
    await waitFor(() => expect(screen.getByText("Last test: Sent")).toBeInTheDocument());

    await userEvent.click(screen.getByRole("checkbox", { name: /high/i }));
    await userEvent.click(screen.getByRole("button", { name: /save preferences/i }));

    await waitFor(() => expect(screen.getByText("Notification preferences saved")).toBeInTheDocument());
    expect(requests).toEqual(expect.arrayContaining([
      expect.objectContaining({ url: "/alerts/notification-channels", method: "GET" }),
      expect.objectContaining({ url: "/alerts/notification-channels/channel-1/diagnostics", method: "GET" }),
      expect.objectContaining({ url: "/alerts/notification-channels/channel-1/test", method: "POST" }),
      expect.objectContaining({ url: "/alerts/notification-preferences", method: "GET" }),
      expect.objectContaining({
        url: "/alerts/notification-preferences",
        method: "PUT",
        body: expect.objectContaining({ enabled: true, timezone: "UTC" }),
      }),
    ]));
  });

  it("creates email and Telegram channels through provider-specific controls", async () => {
    const requests: { url: string; method: string; body?: unknown }[] = [];
    vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      const path = new URL(url).pathname;
      const method = init?.method ?? "GET";
      requests.push({
        url: path,
        method,
        body: init?.body ? JSON.parse(String(init.body)) : undefined,
      });

      if (path === "/auth/me") {
        return json({
          id: "user-1",
          email: "ops@example.com",
          role: "ADMIN",
          status: "ACTIVE",
          emailVerified: true,
        });
      }
      if (path === "/alerts/notification-channels" && method === "GET") {
        return json([]);
      }
      if (path === "/alerts/notification-channels" && method === "POST") {
        const body = init?.body ? JSON.parse(String(init.body)) : {};
        return json({
          id: `channel-${requests.length}`,
          accountId: "account-1",
          createdAt: "2026-09-07T09:00:00Z",
          updatedAt: "2026-09-07T09:00:00Z",
          ...body,
        }, 201);
      }
      if (path.includes("/diagnostics") && method === "GET") {
        return json(diagnostics());
      }
      if (path === "/alerts/notification-preferences" && method === "GET") {
        return json(preference());
      }
      return json({}, 404);
    }));

    render(
      <MemoryRouter>
        <SettingsPage />
      </MemoryRouter>,
    );

    await screen.findByText("Notification Channels");
    await userEvent.selectOptions(screen.getByLabelText("Channel type"), "EMAIL");
    await userEvent.clear(screen.getByPlaceholderText("Recipients"));
    await userEvent.type(screen.getByPlaceholderText("Recipients"), "ops@example.com, lead@example.com");
    await userEvent.type(screen.getByPlaceholderText("Credentials ref"), "vault://alerts/email/ops");
    await userEvent.click(screen.getByRole("button", { name: /add channel/i }));

    await waitFor(() => expect(screen.getByText("Notification channel added")).toBeInTheDocument());
    await userEvent.selectOptions(screen.getByLabelText("Channel type"), "TELEGRAM");
    await userEvent.clear(screen.getByPlaceholderText("Credentials ref"));
    await userEvent.type(screen.getByPlaceholderText("Credentials ref"), "vault://alerts/telegram/ops");
    await userEvent.type(screen.getByPlaceholderText("Telegram chat id"), "-1001234567890");
    await userEvent.selectOptions(screen.getByLabelText("Telegram parse mode"), "MarkdownV2");
    await userEvent.click(screen.getByRole("button", { name: /add channel/i }));

    await waitFor(() => expect(requests.filter((request) => request.method === "POST" && request.url === "/alerts/notification-channels")).toHaveLength(2));
    expect(requests).toEqual(expect.arrayContaining([
      expect.objectContaining({
        method: "POST",
        body: expect.objectContaining({
          channelType: "EMAIL",
          credentialsRef: "vault://alerts/email/ops",
          config: expect.objectContaining({ recipients: ["ops@example.com", "lead@example.com"] }),
        }),
      }),
      expect.objectContaining({
        method: "POST",
        body: expect.objectContaining({
          channelType: "TELEGRAM",
          credentialsRef: "vault://alerts/telegram/ops",
          config: expect.objectContaining({
            chatId: "-1001234567890",
            parseMode: "MarkdownV2",
            disableWebPagePreview: true,
          }),
        }),
      }),
    ]));
  });
});

function diagnostics() {
  return {
    channelId: "channel-1",
    accountId: "account-1",
    channelType: "WEBHOOK",
    enabled: true,
    adapterRegistered: true,
    lastSuccessfulDeliveryAt: "2026-09-07T09:05:00Z",
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

function testAttempt() {
  return {
    id: "attempt-1",
    alertId: null,
    channelId: "channel-1",
    accountId: "account-1",
    channelType: "WEBHOOK",
    deliveryEventType: "NOTIFICATION_TEST",
    status: "SENT",
    retryCount: 0,
    maxAttempts: 3,
    nextAttemptAt: "2026-09-07T09:06:00Z",
    retryExhausted: false,
    lastErrorCode: null,
    providerResponseSummary: "HTTP 202",
    providerMessageId: null,
    createdAt: "2026-09-07T09:06:00Z",
    updatedAt: "2026-09-07T09:06:00Z",
    sentAt: "2026-09-07T09:06:00Z",
  };
}

function preference() {
  return {
    id: "pref-1",
    accountId: "account-1",
    userId: null,
    enabled: true,
    severities: ["WARNING", "HIGH", "CRITICAL"],
    channelTypes: ["WEBHOOK"],
    deviceIds: [],
    quietHoursStart: null,
    quietHoursEnd: null,
    timezone: "UTC",
    criticalBypassQuietHours: true,
    createdAt: "2026-09-07T09:00:00Z",
    updatedAt: "2026-09-07T09:00:00Z",
  };
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json" },
  });
}
