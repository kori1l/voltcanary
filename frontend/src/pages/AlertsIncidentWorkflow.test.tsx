import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AlertsPage } from "./AlertsPage";
import { DeviceDetailsPage } from "./DeviceDetailsPage";

const mlAlert = {
  id: "alert-ml-1",
  deviceId: "device-1",
  accountId: "account-1",
  sourceEventId: "12345678-1234-1234-1234-123456789abc",
  anomalyId: "87654321-4321-4321-4321-cba987654321",
  riskScoreId: null,
  ruleId: "rule-1",
  calculationWindowStart: "2026-08-19T10:00:00Z",
  calculationWindowEnd: "2026-08-19T11:00:00Z",
  calculationInputHash: "abcdef1234567890abcdef1234567890",
  alertType: "POWER_SPIKE",
  title: "Power spike detected",
  description: "A configured power spike rule matched recent telemetry.",
  severity: "HIGH",
  status: "OPEN",
  createdAt: "2026-08-19T11:01:00Z",
  acknowledgedAt: null,
  acknowledgedBy: null,
  resolvedAt: null,
  resolvedBy: null,
  assignedTo: null,
  resolutionReason: null,
};

const device = {
  id: "device-1",
  locationId: "location-1",
  name: "ML alert washer",
  type: "GENERIC_APPLIANCE",
  manufacturer: "VoltCanary",
  model: "verification",
  status: "ONLINE",
  metadata: { semanticDeviceType: "washer" },
};

const rule = {
  id: "rule-1",
  accountId: "account-1",
  name: "ML power spike",
  description: "Synthetic ML incident rule",
  ruleType: "POWER_SPIKE",
  enabled: true,
  scopeType: "DEVICE",
  scopeId: "device-1",
  deviceType: null,
  metric: "activePowerW",
  conditionType: "ABOVE_THRESHOLD",
  threshold: 1200,
  thresholdUnit: "W",
  baselineMultiplier: null,
  minPoints: 1,
  severity: "HIGH",
  cooldownSeconds: 0,
  deduplicationWindowSeconds: 3600,
  notificationChannelIds: ["channel-1"],
  config: {},
  version: 1,
  createdAt: "2026-08-19T10:50:00Z",
  updatedAt: "2026-08-19T10:50:00Z",
};

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe("ML alert incident UI workflows", () => {
  it("shows ML-created alert trace, notification attempts, and lifecycle actions in the alert inbox", async () => {
    const requests: Request[] = [];
    let currentAlert: Record<string, unknown> = { ...mlAlert };
    vi.stubGlobal("prompt", vi.fn(() => "Resolved after inspection."));
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.includes("/alerts/alert-ml-1/notification-attempts")) {
        return json([{
          id: "attempt-1",
          alertId: "alert-ml-1",
          channelId: "channel-1",
          accountId: "account-1",
          channelType: "HTTP",
          status: "PENDING",
          retryCount: 0,
          lastErrorCode: null,
          providerResponse: "queued",
          createdAt: "2026-08-19T11:01:10Z",
          sentAt: null,
        }]);
      }
      if (url.includes("/alerts/alert-ml-1/ack")) {
        currentAlert = { ...currentAlert, status: "ACKNOWLEDGED" };
        return json({ status: "ACKNOWLEDGED", alertId: "alert-ml-1" });
      }
      if (url.includes("/alerts/alert-ml-1/resolve")) {
        currentAlert = { ...currentAlert, status: "RESOLVED", resolutionReason: "Resolved after inspection." };
        return json({ status: "RESOLVED", alertId: "alert-ml-1" });
      }
      if (url.endsWith("/alerts?limit=100") || url.endsWith("/alerts")) return json([currentAlert]);
      if (url.endsWith("/alerts/summary?limit=100")) {
        return json({
          total: 1,
          byStatus: { OPEN: currentAlert.status === "OPEN" ? 1 : 0, ACKNOWLEDGED: currentAlert.status === "ACKNOWLEDGED" ? 1 : 0, RESOLVED: currentAlert.status === "RESOLVED" ? 1 : 0 },
          bySeverity: { INFO: 0, WARNING: 0, HIGH: 1, CRITICAL: 0 },
          byDevice: { "device-1": 1 },
          generatedAt: "2026-08-19T11:02:00Z",
        });
      }
      return defaultJson(url);
    });

    render(<MemoryRouter><AlertsPage /></MemoryRouter>);

    expect(await screen.findAllByText("Power spike detected")).not.toHaveLength(0);
    expect(screen.getAllByText("ML alert washer")).not.toHaveLength(0);
    await userEvent.click(screen.getByRole("button", { name: "Details" }));
    expect(await screen.findByText("12345678")).toBeInTheDocument();
    expect(screen.getByText("abcdef1234")).toBeInTheDocument();
    expect(screen.getByText("Http")).toBeInTheDocument();
    expect(screen.getByText("Pending")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Ack" }));
    await waitFor(() => expect(requests.some((request) => request.url.endsWith("/alerts/alert-ml-1/ack"))).toBe(true));
    await userEvent.click(await screen.findByRole("button", { name: "Resolve" }));
    await waitFor(() => expect(requests.some((request) => request.url.endsWith("/alerts/alert-ml-1/resolve"))).toBe(true));
  });

  it("keeps ML-created alerts visible on the device alert tab and supports acknowledge and resolve", async () => {
    const requests: Request[] = [];
    let currentAlert: Record<string, unknown> = { ...mlAlert };
    vi.stubGlobal("prompt", vi.fn(() => "Resolved from device tab."));
    mockFetch((url, init) => {
      requests.push(new Request(url, init));
      if (url.includes("/alerts/alert-ml-1/ack")) {
        currentAlert = { ...currentAlert, status: "ACKNOWLEDGED" };
        return json({ status: "ACKNOWLEDGED", alertId: "alert-ml-1" });
      }
      if (url.includes("/alerts/alert-ml-1/resolve")) {
        currentAlert = { ...currentAlert, status: "RESOLVED", resolutionReason: "Resolved from device tab." };
        return json({ status: "RESOLVED", alertId: "alert-ml-1" });
      }
      if (url.endsWith("/alerts?limit=100") || url.endsWith("/alerts")) return json([currentAlert]);
      return defaultJson(url);
    });

    render(
      <MemoryRouter initialEntries={["/devices/device-1?tab=alerts"]}>
        <Routes>
          <Route path="/devices/:deviceId" element={<DeviceDetailsPage />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText("Device alerts")).toBeInTheDocument();
    expect(screen.getByText("Power spike detected")).toBeInTheDocument();
    expect(screen.getByText("A configured power spike rule matched recent telemetry.")).toBeInTheDocument();
    expect(screen.getByText("High")).toBeInTheDocument();
    expect(screen.getAllByText("Open")).not.toHaveLength(0);

    await userEvent.click(screen.getByRole("button", { name: "Acknowledge" }));
    await waitFor(() => expect(requests.some((request) => request.url.endsWith("/alerts/alert-ml-1/ack"))).toBe(true));
    await userEvent.click(await screen.findByRole("button", { name: "Resolve" }));
    await waitFor(() => expect(requests.some((request) => request.url.endsWith("/alerts/alert-ml-1/resolve"))).toBe(true));
  });

  it("renders operator-readable errors for alert backend failures", async () => {
    mockFetch((url) => {
      if (url.endsWith("/alerts?limit=100")) {
        return new Response(JSON.stringify({ message: "Alert service temporarily unavailable" }), {
          status: 503,
          headers: { "Content-Type": "application/json" },
        });
      }
      return defaultJson(url);
    });

    render(<MemoryRouter><AlertsPage /></MemoryRouter>);

    expect(await screen.findByText("Alert service temporarily unavailable")).toBeInTheDocument();
  });
});

function mockFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  vi.spyOn(globalThis, "fetch").mockImplementation((input, init) => {
    const url = input instanceof Request ? input.url : input.toString();
    return Promise.resolve(handler(url, init));
  });
}

function defaultJson(url: string) {
  if (url.endsWith("/devices/device-1")) return json(device);
  if (url.endsWith("/devices")) return json([device]);
  if (url.endsWith("/locations")) return json([{ id: "location-1", name: "Line 1", description: null }]);
  if (url.endsWith("/devices/device-1/maintenance-windows")) return json([]);
  if (url.includes("/telemetry/devices/device-1/latest")) {
    return json({ deviceId: "device-1", timestamp: "2026-08-19T11:00:00Z", readings: { activePowerW: 1800, voltageV: 230 } });
  }
  if (url.includes("/telemetry/devices/device-1/readings")) {
    return json({ deviceId: "device-1", metric: "activePowerW", points: [{ timestamp: "2026-08-19T11:00:00Z", value: 1800 }] });
  }
  if (url.includes("/analytics/devices/device-1/baseline")) return json({ deviceId: "device-1", metric: "activePowerW", period: "24h", mean: 800, median: 790, p90: 900, min: 760, max: 1800, sampleCount: 24, calculatedAt: "2026-08-19T11:00:00Z" });
  if (url.includes("/analytics/devices/device-1/risk")) return json({ deviceId: "device-1", score: 70, level: "HIGH", explanations: ["Power spike"], calculatedAt: "2026-08-19T11:00:00Z" });
  if (url.includes("/analytics/devices/device-1/anomalies")) return json([]);
  if (url.includes("/analytics/devices/device-1/recommendations")) return json([]);
  if (url.includes("/analytics/devices/device-1/predictions/latest")) {
    return json({
      deviceId: "device-1",
      modelVersionId: "model-1",
      modelFamily: "ANOMALY_DETECTION",
      modelName: "washer-anomaly",
      modelVersion: "v1",
      modelType: "trained_tabular_anomaly",
      modelStatus: "READY",
      predictionStatus: "TRAINED_MODEL",
      policySource: "DEVICE",
      deploymentMode: "ACTIVE",
      fallbackReason: "",
      anomalyScore: 0.91,
      degradationScore: 0.2,
      confidence: 0.88,
      inputHash: "abcdef1234567890abcdef1234567890",
      riskDrivers: [],
      signals: [],
      explanations: [],
      recommendedActions: [],
      createdAt: "2026-08-19T11:00:00Z",
    });
  }
  if (url.endsWith("/devices/device-1/data-sources")) return json([]);
  if (url.endsWith("/devices/device-1/sensors")) return json([]);
  if (url.endsWith("/devices/device-1/analytics-config")) return json({ enabled: true, config: {} });
  if (url.includes("/simulation/devices/device-1/config")) return json({ deviceId: "device-1", profile: "FRIDGE", protocol: "HTTP", sourceId: "source-1", dataSourceId: null, yamlConfig: "" });
  if (url.includes("/simulation/devices/device-1/status")) return json(null);
  if (url.includes("/simulation/devices/device-1/runs")) return json([]);
  if (url.endsWith("/alerts/notification-channels")) return json([]);
  if (url.endsWith("/alert-rules")) return json([rule]);
  if (url.endsWith("/alerts/summary?limit=100")) {
    return json({ total: 1, byStatus: { OPEN: 1, ACKNOWLEDGED: 0, RESOLVED: 0 }, bySeverity: { INFO: 0, WARNING: 0, HIGH: 1, CRITICAL: 0 }, byDevice: { "device-1": 1 }, generatedAt: "2026-08-19T11:02:00Z" });
  }
  return json({});
}

function json(value: unknown) {
  return new Response(JSON.stringify(value), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  });
}
