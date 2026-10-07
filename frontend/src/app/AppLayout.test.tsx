import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { AppLayout } from "./AppLayout";

vi.mock("../auth/authApi", () => ({ me: vi.fn(async () => ({ role: "ADMIN" })), logout: vi.fn() }));
vi.mock("../aiAgent/AssistantDrawer", () => ({ AssistantDrawer: () => null }));
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("console navigation", () => {
  it("marks only the most specific destination and closes the menu after navigation", async () => {
    vi.spyOn(window, "scrollTo").mockImplementation(() => {});
    render(<MemoryRouter initialEntries={["/settings/ml"]}><Routes><Route element={<AppLayout />}><Route path="*" element={<h1>Workspace</h1>} /></Route></Routes></MemoryRouter>);
    await waitFor(() => expect(screen.getByRole("link", { name: "ML Settings" })).toHaveClass("active"));
    expect(screen.getByRole("link", { name: /^Settings$/ })).not.toHaveClass("active");
    expect(screen.getByRole("link", { name: /^Settings$/ })).not.toHaveAttribute("aria-current");
    await userEvent.click(screen.getByRole("button", { name: "Menu" }));
    expect(screen.getByRole("button", { name: "Close menu" })).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(screen.getByRole("link", { name: "Devices" }));
    expect(screen.getByRole("button", { name: "Menu" })).toHaveAttribute("aria-expanded", "false");
    expect(window.scrollTo).toHaveBeenCalledWith({ top: 0, left: 0 });
  });
});
