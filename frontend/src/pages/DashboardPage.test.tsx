import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { DashboardPage } from "./DashboardPage";
import { isFreshSignal } from "../features/dashboard/useFleetDashboard";

afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe("fleet dashboard evidence", () => {
  it("keeps inventory when alerts fail and marks retained counts stale on a failed refresh", async () => {
    let alertsFail = true;
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      const path = new URL(url).pathname;
      const data = path === "/devices" ? [{ id: "d1", name: "Kitchen fridge", status: "ACTIVE", type: "FRIDGE" }] : path === "/locations" ? [] : path.endsWith("/latest") ? { deviceId: "d1", timestamp: "2020-01-01T00:00:00Z", readings: { activePowerW: 10 } } : path === "/alerts" && !alertsFail ? [] : { message: "Alerts offline" };
      return new Response(JSON.stringify(data), { status: path === "/alerts" && alertsFail ? 503 : 200, headers: { "Content-Type": "application/json" } });
    }));
    render(<MemoryRouter><DashboardPage /></MemoryRouter>);
    await screen.findByText("Kitchen fridge");
    expect(within(screen.getByText("Registered devices (up to 200)").closest("article")!).getByText("1")).toBeInTheDocument();
    expect(within(screen.getByText("Active alerts (up to 100)").closest("article")!).getByText("Unavailable")).toBeInTheDocument();
    expect(screen.getByText("Stale signal")).toBeInTheDocument();
    alertsFail = false;
    await userEvent.click(screen.getByRole("button", { name: "Retry alerts" }));
    await waitFor(() => expect(within(screen.getByText("Active alerts (up to 100)").closest("article")!).getByText("0")).toBeInTheDocument());
    alertsFail = true;
    await userEvent.click(screen.getByRole("button", { name: "Refresh dashboard" }));
    await screen.findByText("alerts: showing stale data.");
    expect(within(screen.getByText("Active alerts (up to 100)").closest("article")!).getByText("0")).toBeInTheDocument();
  });

  it("requires a measured recent signal for freshness", () => {
    const now = Date.parse("2026-10-06T10:00:00Z");
    expect(isFreshSignal(null, now)).toBe(false);
    expect(isFreshSignal({ deviceId: "d", readings: { power: 0 }, timestamp: "2026-10-06T09:58:00Z" }, now)).toBe(true);
    expect(isFreshSignal({ deviceId: "d", readings: {}, timestamp: "2026-10-06T09:58:00Z" }, now)).toBe(false);
    expect(isFreshSignal({ deviceId: "d", readings: { power: 1 }, timestamp: "2026-10-06T09:00:00Z" }, now)).toBe(false);
  });
});
