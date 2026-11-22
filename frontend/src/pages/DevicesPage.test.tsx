import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { DevicesPage } from "./DevicesPage";
import { clearTokens, setSession } from "../api/client";
import { writeDeviceSetupCheckpoint } from "../devices/deviceSetupCheckpoint";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  clearTokens();
  localStorage.clear();
});
it("unlocks restored source reconciliation even when the wizard already has no error and is on Review", async () => {
  setSession(`${btoa("{}")}.${btoa(JSON.stringify({ sub: "user-fixture" }))}.fixture`, "fixture");
  const account = {
    id: "account-fixture",
    userId: "user-fixture",
    name: "Fixture",
    status: "ACTIVE" as const,
  };
  writeDeviceSetupCheckpoint(account, {
    deviceId: "created-device",
    pendingStage: "source",
    name: "Created fridge",
    type: "FRIDGE",
    mode: "API",
    externalId: "plug-1",
    profile: "FRIDGE",
    preset: "NORMAL_OPERATION",
    startImmediately: false,
  });
  const methods: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    methods.push(init?.method ?? "GET");
    const path = new URL(String(input)).pathname;
    let body: unknown = [];
    if (path === "/accounts/current") body = account;
    if (path === "/devices/created-device")
      body = {
        id: "created-device",
        name: "Created fridge",
        type: "FRIDGE",
        status: "ACTIVE",
        locationId: null,
      };
    if (path.endsWith("/data-sources"))
      body = [
        {
          id: "canonical-source",
          deviceId: "created-device",
          externalId: "plug-1",
          sourceType: "SMART_PLUG",
          protocol: "REST",
        },
      ];
    if (path.endsWith("/latest"))
      body = { deviceId: "created-device", timestamp: "2026-10-08T12:00:00Z", readings: {} };
    return new Response(JSON.stringify(body), { status: 200 });
  });
  render(
    <MemoryRouter initialEntries={["/devices"]}>
      <DevicesPage />
    </MemoryRouter>,
  );
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
    if (path === "/devices")
      body = [
        {
          id: "fridge",
          name: "Kitchen fridge",
          type: "FRIDGE",
          status: "ACTIVE",
          locationId: "kitchen",
        },
      ];
    if (path === "/locations") body = [{ id: "kitchen", name: "Kitchen", tags: [] }];
    if (path.endsWith("/latest"))
      body = {
        deviceId: "fridge",
        readings: { activePowerW: 120 },
        timestamp: new Date().toISOString(),
      };
    if (path.endsWith("/risk")) body = { deviceId: "fridge", level: "LOW", score: 24 };
    return new Response(JSON.stringify(body), { status: 200 });
  });
  const { container } = render(
    <MemoryRouter initialEntries={["/devices"]}>
      <DevicesPage />
    </MemoryRouter>,
  );
  expect(screen.getByLabelText("Inventory view")).toHaveValue("cards");
  const tile = await screen.findByRole("link", { name: /Kitchen fridge/ });
  expect(tile).toHaveClass("device-icon-card");
  expect(tile.querySelector("svg")).not.toBeNull();
  expect(screen.getByRole("heading", { name: "Kitchen" })).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Next page" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Previous page" })).not.toBeInTheDocument();
  expect(screen.getByText("1 devices on this page")).toBeVisible();
  fireEvent.change(screen.getByLabelText("Search devices"), { target: { value: "Kitchen" } });
  fireEvent.change(screen.getByLabelText("Inventory view"), { target: { value: "table" } });
  await waitFor(() => expect(screen.getByLabelText("Search devices")).toHaveValue("Kitchen"));
  expect(container.querySelector(".device-icon-card")).toBeNull();
  expect(screen.getByRole("table")).toBeInTheDocument();
});
it("shows missing risk without a service error and retains inventory when setup references fail", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const path = new URL(String(input)).pathname;
    if (path === "/devices")
      return new Response(
        JSON.stringify([
          {
            id: "owned",
            name: "Teaching fridge",
            type: "FRIDGE",
            status: "ACTIVE",
            locationId: null,
          },
        ]),
        { status: 200 },
      );
    if (path.endsWith("/latest"))
      return new Response(
        JSON.stringify({ deviceId: "owned", readings: {}, timestamp: new Date().toISOString() }),
        { status: 200 },
      );
    if (path.endsWith("/risk"))
      return new Response(JSON.stringify({ message: "No risk score for device" }), { status: 404 });
    return new Response(JSON.stringify({ message: "Reference service unavailable" }), {
      status: 503,
    });
  });
  render(
    <MemoryRouter initialEntries={["/devices"]}>
      <DevicesPage />
    </MemoryRouter>,
  );
  expect(await screen.findByRole("link", { name: /Teaching fridge/ })).toBeVisible();
  expect(await screen.findByText("Risk not calculated")).toBeVisible();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(screen.getByText("Some device options are unavailable")).toBeVisible();
});
it("does not request simulation presets during normal inventory browsing", async () => {
  const requests: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    const path = new URL(String(input)).pathname;
    requests.push(path);
    return new Response(JSON.stringify([]), { status: 200 });
  });
  render(
    <MemoryRouter initialEntries={["/devices"]}>
      <DevicesPage />
    </MemoryRouter>,
  );
  await screen.findByText("No devices match these filters");
  expect(requests).not.toContain("/simulation/presets");
  expect(screen.queryByText("Some device options are unavailable")).not.toBeInTheDocument();
});
