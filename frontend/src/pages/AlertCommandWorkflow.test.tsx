import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { AlertsPage } from "./AlertsPage";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it("sends one acknowledge for double click while another alert remains operable", async () => {
  let release!: (response: Response) => void;
  const ackIds: string[] = [];
  const alerts = ["A", "B"].map((id) => ({ id, accountId: "account-1", deviceId: "device-1", alertType: "THRESHOLD", title: `Incident ${id}`, description: "Recorded threshold", severity: "HIGH", status: "OPEN", createdAt: "2026-10-08T00:00:00Z" }));
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const path = new URL(String(input)).pathname;
    if (path.endsWith("/ack")) { const segments = path.split("/"); const id = segments[segments.length - 2]; ackIds.push(id); if (id === "A") return new Promise<Response>((resolve) => { release = resolve; }); return new Response(JSON.stringify({ alertId: id, status: "ACKNOWLEDGED" })); }
    const data = path === "/alerts/page" ? { content: alerts, page: 0, size: 100, totalElements: alerts.length, totalPages: 1, hasNext: false } : path === "/alerts/summary" ? { byStatus: { OPEN: 2 }, bySeverity: { HIGH: 2 } } : [];
    return new Response(JSON.stringify(data));
  });
  render(<MemoryRouter initialEntries={["/alerts"]}><AlertsPage /></MemoryRouter>);
  const a = (await screen.findAllByText("Incident A")).find((element) => element.closest("article"))!.closest("article")!;
  const b = screen.getByText("Incident B").closest("article")!;
  await userEvent.dblClick(within(a).getByText("Acknowledge"));
  expect(within(a).getByText("Acknowledge")).toBeDisabled(); expect(within(b).getByText("Acknowledge")).toBeEnabled();
  await userEvent.click(within(b).getByText("Acknowledge"));
  expect(ackIds).toEqual(["A", "B"]);
  await act(async () => release(new Response(JSON.stringify({ alertId: "A", status: "ACKNOWLEDGED" }))));
});
