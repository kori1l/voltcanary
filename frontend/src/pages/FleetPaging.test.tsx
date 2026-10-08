import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { DevicesPage } from "./DevicesPage";
import { AlertsPage } from "./AlertsPage";
import { MaintenanceCalendarPage } from "../maintenance/MaintenanceCalendarPage";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const json = (value: unknown) => new Response(JSON.stringify(value), { status: 200 });
it("reaches device 101, debounces search, resets the page and preserves independent references", async () => {
  const requests: URL[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = new URL(String(input)); requests.push(url);
    if (url.pathname === "/devices") {
      const offset = Number(url.searchParams.get("offset"));
      return json(Array.from({ length: offset ? 21 : 101 }, (_, index) => ({ id: `device-${offset + index + 1}`, name: `Device ${offset + index + 1}`, type: "FRIDGE", status: "ACTIVE", locationId: null })));
    }
    if (url.pathname.endsWith("/latest")) return json({ timestamp: "2026-10-08T12:00:00Z", readings: {} });
    if (url.pathname.endsWith("/risk")) return json({ level: "LOW", score: 0 });
    return json([]);
  });
  render(<MemoryRouter initialEntries={["/devices"]}><DevicesPage /></MemoryRouter>);
  await screen.findByText("Device 100");
  await userEvent.click(screen.getByRole("button", { name: "Next page" }));
  await screen.findByText("Device 101");
  fireEvent.change(screen.getByLabelText("Search devices"), { target: { value: "s" } });
  fireEvent.change(screen.getByLabelText("Search devices"), { target: { value: "solar" } });
  await waitFor(() => expect(requests.filter((url) => url.pathname === "/devices").slice(-1)[0]?.searchParams.get("search")).toBe("solar"));
  const reads = requests.filter((url) => url.pathname === "/devices");
  expect(reads).toHaveLength(3);
  expect(reads[reads.length - 1]?.searchParams.get("offset")).toBe("0");
  expect(requests.filter((url) => url.pathname === "/device-types")).toHaveLength(1);
  expect(screen.getByRole("combobox", { name: "Filter by registration status" })).toContainHTML('value="DISABLED"');
});
it("continues maintenance beyond operation 201 without requiring it in the initial device roster", async () => {
  const operationReads: number[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = new URL(String(input));
    if (url.pathname === "/maintenance-operations") {
      const offset = Number(url.searchParams.get("offset")); operationReads.push(offset);
      return json(Array.from({ length: offset === 200 ? 25 : 101 }, (_, index) => ({ id: `op-${offset + index + 1}`, title: `Operation ${offset + index + 1}`, startAt: "2026-10-08T10:00:00Z", endAt: "2026-10-08T11:00:00Z", status: "SCHEDULED", devices: [{ deviceId: "outside-roster", deviceName: "Device 121", deviceType: "FRIDGE", locationId: null, locationName: null }], version: 1 })));
    }
    return json([]);
  });
  render(<MemoryRouter initialEntries={["/maintenance?view=agenda&from=2026-10-08&to=2026-10-09"]}><MaintenanceCalendarPage /></MemoryRouter>);
  await screen.findByText("Operation 100");
  await userEvent.click(screen.getByRole("button", { name: "Next page" }));
  await screen.findByText("Operation 200");
  await userEvent.click(screen.getByRole("button", { name: "Next page" }));
  await screen.findByText("Operation 201");
  expect(operationReads).toEqual([0, 100, 200]);
  expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
  expect(screen.getByText(/Page 3 · 25 loaded · Total unknown/)).toBeInTheDocument();
});
it("uses authoritative alert totals and exposes an empty page without reloading references", async () => {
  const requests: URL[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const url = new URL(String(input)); requests.push(url);
    if (url.pathname === "/alerts/page") {
      const page = Number(url.searchParams.get("page"));
      return json({ content: [], page, size: 100, totalElements: 101, totalPages: 2, hasNext: page === 0 });
    }
    if (url.pathname === "/alerts/summary") return json({ total: 101, byStatus: {}, bySeverity: {}, byDevice: {}, generatedAt: "2026-10-08T12:00:00Z" });
    return json([]);
  });
  render(<MemoryRouter initialEntries={["/alerts"]}><AlertsPage /></MemoryRouter>);
  await screen.findByText(/Page 1 · 0 loaded · 101 total/);
  await userEvent.click(screen.getByRole("button", { name: "Next page" }));
  await screen.findByText(/Page 2 · 0 loaded · 101 total/);
  expect(screen.getByRole("button", { name: "Next page" })).toBeDisabled();
  expect(requests.filter((url) => url.pathname === "/alert-rules")).toHaveLength(1);
  expect(requests.filter((url) => url.pathname === "/alerts/summary")).toHaveLength(1);
});
