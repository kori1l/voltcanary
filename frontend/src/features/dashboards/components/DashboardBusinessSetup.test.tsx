import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { EnergyTariffSetup } from "./EnergyTariffSetup";
import { OperationalFactsSetup } from "./OperationalFactsSetup";
import { DashboardSharingSettings } from "./DashboardSharingSettings";
import * as business from "../model/dashboardBusinessApi";
import * as sharing from "../model/dashboardSharingApi";
import { listDataSources } from "../../../devices/devicesApi";
import { listWorkspaces } from "../../workspaces/workspacesApi";
vi.mock("../model/dashboardBusinessApi", () => ({
  listEnergyTariffs: vi.fn(),
  saveEnergyTariff: vi.fn(),
  archiveEnergyTariff: vi.fn(),
  listWorkspaceSources: vi.fn(),
  listOperationalFacts: vi.fn(),
  listOperatingSchedules: vi.fn(),
  saveOperationalFact: vi.fn(),
  saveOperatingSchedule: vi.fn(),
}));
vi.mock("../model/dashboardSharingApi", () => ({
  dashboardAudience: vi.fn(),
  previewDashboardAudience: vi.fn(),
  saveDashboardAudience: vi.fn(),
}));
vi.mock("../../workspaces/workspacesApi", () => ({
  listWorkspaces: vi.fn().mockResolvedValue([{ id: "workspace", name: "Workshop" }]),
}));
vi.mock("../../../devices/devicesApi", () => ({ listDataSources: vi.fn().mockResolvedValue([]) }));
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(listDataSources).mockResolvedValue([]);
  vi.mocked(listWorkspaces).mockResolvedValue([{ id: "workspace", name: "Workshop" }] as never);
  vi.mocked(business.listEnergyTariffs).mockResolvedValue([]);
  vi.mocked(business.listWorkspaceSources).mockResolvedValue([
    { id: "source", externalId: "Confirmed source" },
  ] as never);
  vi.mocked(business.listOperationalFacts).mockResolvedValue([]);
  vi.mocked(business.listOperatingSchedules).mockResolvedValue([]);
});
afterEach(cleanup);

it("requires review and sends a decimal tariff with the current revision and explicit workspace scope", async () => {
  vi.mocked(business.listEnergyTariffs).mockResolvedValue([
    {
      tariffId: "tariff",
      version: 4,
      ratePerKWh: "0.153",
      currency: "EUR",
      effectiveFrom: "2026-10-01T00:00:00Z",
      effectiveTo: "2026-11-01T00:00:00Z",
      taxesIncluded: true,
      archived: false,
    },
  ] as never);
  render(<EnergyTariffSetup accountId="account" workspaceId="workspace" />);
  await screen.findByRole("option", { name: "tariff" });
  fireEvent.change(screen.getByLabelText("Tariff"), { target: { value: "tariff" } });
  fireEvent.change(screen.getByLabelText("Revision or correction reason"), {
    target: { value: "Reviewed backdated correction" },
  });
  expect(screen.getByRole("button", { name: "Save tariff revision" })).toBeDisabled();
  fireEvent.click(screen.getByLabelText("I reviewed this rate, period and tax policy"));
  fireEvent.click(screen.getByRole("button", { name: "Save tariff revision" }));
  await waitFor(() =>
    expect(business.saveEnergyTariff).toHaveBeenCalledWith(
      expect.objectContaining({
        tariffId: "tariff",
        version: 4,
        accountId: "account",
        workspaceId: "workspace",
        ratePerKWh: "0.153",
        taxesIncluded: true,
        reason: "Reviewed backdated correction",
      }),
    ),
  );
});

it("records an incomplete confirmed cycle without inventing its end", async () => {
  render(<OperationalFactsSetup deviceId="device" accountId="account" workspaceId="workspace" />);
  await screen.findByRole("option", { name: "Confirmed source" });
  fireEvent.change(screen.getByLabelText("Source"), { target: { value: "source" } });
  fireEvent.change(screen.getByLabelText("Source event identity"), {
    target: { value: "cycle-1" },
  });
  fireEvent.change(screen.getByLabelText("Completeness"), { target: { value: "INCOMPLETE" } });
  fireEvent.change(screen.getByLabelText("Starts (UTC)"), {
    target: { value: "2026-10-01T10:00" },
  });
  fireEvent.change(screen.getByLabelText("Reason"), {
    target: { value: "Operator confirmed start" },
  });
  fireEvent.click(screen.getByLabelText("I reviewed the confirmed fact or schedule"));
  fireEvent.click(screen.getByRole("button", { name: "Save confirmed fact" }));
  await waitFor(() =>
    expect(business.saveOperationalFact).toHaveBeenCalledWith(
      expect.objectContaining({
        deviceId: "device",
        sourceId: "source",
        sourceEventId: "cycle-1",
        completeness: "INCOMPLETE",
        startAt: "2026-10-01T10:00:00.000Z",
        endAt: null,
      }),
      "account",
      "workspace",
    ),
  );
});

it("preserves a schedule revision's timezone instead of replacing it with the browser timezone", async () => {
  vi.mocked(business.listOperatingSchedules).mockResolvedValue([
    {
      scheduleId: "schedule",
      version: 2,
      deviceId: "device",
      timezone: "America/New_York",
      effectiveFrom: "2026-10-01T00:00:00Z",
      effectiveTo: "2026-11-01T00:00:00Z",
      weekSlots: [{ dayOfWeek: 1, from: "08:00", to: "18:00" }],
      holidays: [],
      reason: "Old",
    },
  ]);
  render(<OperationalFactsSetup deviceId="device" />);
  fireEvent.click(screen.getByRole("button", { name: "Load recent facts and schedule revisions" }));
  await screen.findByRole("option", { name: "schedule" });
  fireEvent.change(screen.getByLabelText("Schedule revision"), { target: { value: "schedule" } });
  expect(screen.getByLabelText("Schedule timezone")).toHaveValue("America/New_York");
  fireEvent.change(screen.getByLabelText("Reason"), { target: { value: "Holiday review" } });
  fireEvent.click(screen.getByLabelText("I reviewed the confirmed fact or schedule"));
  fireEvent.click(screen.getByRole("button", { name: "Save schedule" }));
  await waitFor(() =>
    expect(business.saveOperatingSchedule).toHaveBeenCalledWith(
      expect.objectContaining({ scheduleId: "schedule", version: 2, timezone: "America/New_York" }),
      undefined,
      undefined,
    ),
  );
});

it("discards an old equipment history response after switching equipment", async () => {
  let resolve!: (value: business.OperationalFact[]) => void;
  vi.mocked(business.listOperationalFacts).mockReturnValue(
    new Promise((r) => {
      resolve = r;
    }),
  );
  const view = render(<OperationalFactsSetup deviceId="old" />);
  fireEvent.click(screen.getByRole("button", { name: "Load recent facts and schedule revisions" }));
  view.rerender(<OperationalFactsSetup deviceId="new" />);
  resolve([
    {
      eventId: "old-event",
      version: 1,
      deviceId: "old",
      kind: "CYCLE",
      startAt: "2026-10-01T10:00:00Z",
    },
  ] as never);
  await waitFor(() =>
    expect(screen.getByLabelText("Fact revision").querySelectorAll("option")).toHaveLength(1),
  );
  expect(screen.getByLabelText("I reviewed the confirmed fact or schedule")).not.toBeChecked();
});

it("saves dashboard capabilities separately and clears user preview when changing to a team", async () => {
  vi.mocked(sharing.dashboardAudience).mockResolvedValue({
    dashboardId: "dashboard",
    workspaceId: "workspace",
    version: 3,
    audience: [],
  });
  vi.mocked(sharing.previewDashboardAudience).mockResolvedValue({
    recipients: [
      {
        userId: "recipient",
        widgets: [{ widgetId: "equipment", usable: true, resourceAccessGranted: false }],
      },
    ],
  });
  render(
    <MemoryRouter>
      <DashboardSharingSettings id="dashboard" />
    </MemoryRouter>,
  );
  await screen.findByLabelText("Active workspace recipient ID");
  fireEvent.change(screen.getByLabelText("Active workspace recipient ID"), {
    target: { value: "recipient" },
  });
  fireEvent.click(screen.getByRole("button", { name: "Preview resource usability" }));
  await screen.findByText(/Has some authorized evidence/);
  expect(sharing.saveDashboardAudience).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("Recipient kind"), { target: { value: "TEAM" } });
  expect(screen.queryByText(/Has some authorized evidence/)).not.toBeInTheDocument();
  fireEvent.click(screen.getByLabelText("edit"));
  fireEvent.click(
    screen.getByLabelText("I reviewed the workspace, recipient and explicit capabilities"),
  );
  fireEvent.click(screen.getByRole("button", { name: "Save reviewed dashboard grant" }));
  await waitFor(() =>
    expect(sharing.saveDashboardAudience).toHaveBeenCalledWith(
      "dashboard",
      expect.objectContaining({
        version: 3,
        audience: [{ subjectId: "recipient", subjectKind: "TEAM", capabilities: ["VIEW", "EDIT"] }],
      }),
    ),
  );
});
