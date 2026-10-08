import { useEffect } from "react";
import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { createMemoryRouter, RouterProvider, useLocation } from "react-router-dom";
import { apiRequest, clearTokens, setTokens } from "../api/client";
import { RequireAuth } from "../app/App";
import { LoginPage } from "../pages/LoginPage";

afterEach(() => { cleanup(); clearTokens(); localStorage.clear(); vi.restoreAllMocks(); });
function PrivatePage() {
  const location = useLocation();
  useEffect(() => { void Promise.allSettled([apiRequest("/devices"), apiRequest("/alerts")]); }, []);
  return <p>Private {location.pathname}{location.search}</p>;
}
it("returns from definitive expiry to the exact device/metric/incident interval after login", async () => {
  setTokens("expired", "rejected"); let refreshes = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (String(input).endsWith("/auth/login")) return new Response(JSON.stringify({ accessToken: "valid", refreshToken: "valid-refresh" }));
    if (String(input).endsWith("/auth/refresh")) { refreshes++; return new Response("{}", { status: 401 }); }
    return new Response("{}", { status: new Headers(init?.headers).get("Authorization") === "Bearer valid" ? 200 : 401 });
  });
  const deep = "/devices/device-1?tab=telemetry&metric=power&from=2026-10-01T00%3A00%3A00Z&to=2026-10-02T00%3A00%3A00Z&alertId=alert-1";
  const router = createMemoryRouter([{ path: "/devices/:id", element: <RequireAuth><PrivatePage /></RequireAuth> }, { path: "/login", element: <LoginPage /> }], { initialEntries: [deep] });
  render(<RouterProvider router={router} />);
  await screen.findByText(/Your session expired/);
  await userEvent.type(screen.getByLabelText("Email"), "fixture@example.invalid");
  await userEvent.type(screen.getByLabelText(/Password/, { selector: "input" }), "test-only");
  await userEvent.click(screen.getByRole("button", { name: "Sign in" }));
  await screen.findByText(/Private \/devices\/device-1/);
  expect(router.state.location.pathname + router.state.location.search).toBe(deep);
  expect(refreshes).toBe(1);
});
