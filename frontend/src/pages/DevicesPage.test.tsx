import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { DevicesPage } from "./DevicesPage";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
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
  const { container } = render(<MemoryRouter><DevicesPage /></MemoryRouter>);
  expect(screen.getByLabelText("Inventory view")).toHaveValue("cards");
  const tile = await screen.findByRole("link", { name: /Kitchen fridge/ });
  expect(tile).toHaveClass("device-icon-card");
  expect(tile.querySelector("svg")).not.toBeNull();
  expect(screen.getByRole("heading", { name: "Kitchen" })).toBeInTheDocument();
  fireEvent.change(screen.getByLabelText("Search devices"), { target: { value: "Kitchen" } });
  fireEvent.change(screen.getByLabelText("Inventory view"), { target: { value: "table" } });
  expect(screen.getByLabelText("Search devices")).toHaveValue("Kitchen");
  expect(container.querySelector(".device-icon-card")).toBeNull();
  expect(screen.getByRole("table")).toBeInTheDocument();
});
