import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { DevicesPage } from "./DevicesPage";
import { clearTokens, setTokens } from "../api/client";
import { writeDeviceSetupCheckpoint } from "../devices/deviceSetupCheckpoint";

afterEach(() => { cleanup(); vi.restoreAllMocks(); clearTokens(); localStorage.clear(); });
it("unlocks restored source reconciliation even when the wizard already has no error and is on Review", async () => {
  setTokens(`${btoa("{}")}.${btoa(JSON.stringify({ sub: "user-fixture" }))}.fixture`, "fixture");
  const account = { id: "account-fixture", userId: "user-fixture", name: "Fixture", status: "ACTIVE" as const };
  writeDeviceSetupCheckpoint(account, { deviceId: "created-device", pendingStage: "source", name: "Created fridge", type: "FRIDGE", mode: "API", externalId: "plug-1", profile: "FRIDGE", preset: "NORMAL_OPERATION", startImmediately: false });
  const methods: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    methods.push(init?.method ?? "GET"); const path = new URL(String(input)).pathname;
    let body: unknown = [];
    if (path === "/accounts/current") body = account;
    if (path === "/devices/created-device") body = { id: "created-device", name: "Created fridge", type: "FRIDGE", status: "ACTIVE", locationId: null };
    if (path.endsWith("/data-sources")) body = [{ id: "canonical-source", deviceId: "created-device", externalId: "plug-1", sourceType: "SMART_PLUG", protocol: "REST" }];
    if (path.endsWith("/latest")) body = { deviceId: "created-device", timestamp: "2026-10-08T12:00:00Z", readings: {} };
    return new Response(JSON.stringify(body), { status: 200 });
  });
  render(<MemoryRouter initialEntries={["/devices"]}><DevicesPage /></MemoryRouter>);
  await waitFor(() => expect(screen.getByRole("button", { name: "Add device" })).toBeEnabled());
  fireEvent.click(screen.getByRole("button", { name: "Add device" }));
  const reviewed = await screen.findByRole("button", { name: "Use reviewed record" });
  expect(screen.getByRole("button", { name: "Resume setup" })).toBeDisabled();
  fireEvent.click(reviewed);
  await waitFor(() => expect(screen.getByRole("button", { name: "Resume setup" })).toBeEnabled());
  fireEvent.click(screen.getByRole("button", { name: "Resume setup" }));
  await screen.findByRole("heading", { name: "Device registered" });
  expect(methods.every((method) => method === "GET")).toBe(true);
});
it("starts with recognizable location tiles and retains search when switching to the table", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const path = new URL(String(input)).pathname;
    let body: unknown = [];
    if (path === "/devices") body = [{ id: "fridge", name: "Kitchen fridge", type: "FRIDGE", status: "ACTIVE", locationId: "kitchen" }];
    if (path === "/locations") body = [{ id: "kitchen", name: "Kitchen", tags: [] }];
    if (path.endsWith("/latest")) body = { deviceId: "fridge", readings: { activePowerW: 120 }, timestamp: new Date().toISOString() };
    if (path.endsWith("/risk")) body = { deviceId: "fridge", level: "LOW", score: 24 };
    return new Response(JSON.stringify(body), { status: 200 });
  });
  const { container } = render(<MemoryRouter initialEntries={["/devices"]}><DevicesPage /></MemoryRouter>);
  expect(screen.getByLabelText("Inventory view")).toHaveValue("cards");
  const tile = await screen.findByRole("link", { name: /Kitchen fridge/ });
  expect(tile).toHaveClass("device-icon-card");
  expect(tile.querySelector("svg")).not.toBeNull();
  expect(screen.getByRole("heading", { name: "Kitchen" })).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Search devices"), { target: { value: "Kitchen" } });
  fireEvent.change(screen.getByLabelText("Inventory view"), { target: { value: "table" } });
  await waitFor(() => expect(screen.getByLabelText("Search devices")).toHaveValue("Kitchen"));
  expect(container.querySelector(".device-icon-card")).toBeNull();
  expect(screen.getByRole("table")).toBeInTheDocument();
});
