import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { maintenanceImpact, validMaintenanceInterval } from "./MaintenanceFields";
import { MaintenanceOutcomeEditor } from "./MaintenanceOutcomeEditor";
import { MaintenanceOperation } from "./maintenanceApi";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const operation: MaintenanceOperation = { id: "operation", accountId: "account", title: "Kitchen inspection", description: null, status: "FINISHED", startAt: "2026-10-01T10:00:00Z", endAt: "2026-10-01T11:00:00Z", suppressAlerts: false, excludeFromAnalytics: true, excludeFromMl: false, cancelledAt: null, cancellationReason: null, devices: [{ deviceId: "canonical-device", deviceName: "Kitchen fridge", deviceType: "FRIDGE", locationId: null, locationName: null, windowId: "canonical-window", status: "FINISHED" }], createdBy: null, updatedBy: null, createdAt: "2026-10-01T09:00:00Z", updatedAt: "2026-10-01T11:00:00Z", version: 1 };
describe("maintenance evidence", () => {
  it("validates intervals and describes each exclusion independently", () => {
    expect(validMaintenanceInterval("2026-10-01T11:00", "2026-10-01T10:00")).toBe(false);
    expect(validMaintenanceInterval("2026-10-01T10:00", "2026-10-01T11:00")).toBe(true);
    expect(maintenanceImpact(operation)).toContain("Alerts remain enabled; samples excluded from analytics; samples included in ML");
  });
  it("clears hidden failure fields from normal outcomes and uses canonical device/window IDs", async () => {
    const calls: RequestInit[] = [];
    vi.spyOn(globalThis, "fetch").mockImplementation(async (_input, init) => { if (init?.method === "POST") { calls.push(init); return new Response(JSON.stringify({ ...JSON.parse(init.body as string), id: "saved" }), { status: 200 }); } return new Response("[]", { status: 200 }); });
    render(<MaintenanceOutcomeEditor operation={operation} onSaved={vi.fn()} />);
    await userEvent.selectOptions(screen.getByLabelText("Outcome"), "CONFIRMED_FAILURE");
    await userEvent.type(screen.getByLabelText("Failure mode"), "Compressor stall");
    await userEvent.selectOptions(screen.getByLabelText("Outcome"), "INSPECTION_NORMAL");
    await userEvent.type(screen.getByLabelText("Technician notes"), "Inspection passed");
    await userEvent.click(screen.getByRole("button", { name: "Record outcome" }));
    expect(await screen.findByText("Inspection passed", { selector: "p" })).toBeInTheDocument();
    expect(JSON.parse(calls[0].body as string)).toMatchObject({ deviceId: "canonical-device", maintenanceWindowId: "canonical-window", outcomeType: "INSPECTION_NORMAL", failureMode: null, component: null, severity: null, source: "OPERATOR" });
  });
});
