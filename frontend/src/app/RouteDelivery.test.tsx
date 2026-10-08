import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, useLocation, Routes, Route, Outlet, Link } from "react-router-dom";
import { expect, it, vi } from "vitest";
import { RouteDelivery } from "./RouteDelivery";

it("contains a failed chunk and explicitly retries without changing route context", async () => {
  const consoleError = vi.spyOn(console,"error").mockImplementation(() => {});
  function Page() { const location=useLocation(); return <p>Loaded {location.pathname}{location.search}</p>; }
  const load = vi.fn().mockRejectedValueOnce(new Error("chunk unavailable")).mockResolvedValueOnce({default:Page});
  render(<MemoryRouter initialEntries={["/settings/ml?tab=training&horizon=7"]}><RouteDelivery load={load} label="ML workspace" /></MemoryRouter>);
  expect(screen.getByRole("status")).toHaveTextContent("Loading ML workspace");
  expect(await screen.findByRole("alert")).toHaveTextContent("Your route and filters are preserved");
  expect(load).toHaveBeenCalledTimes(1);
  await userEvent.click(screen.getByRole("button",{name:"Retry page loading"}));
  expect(await screen.findByText("Loaded /settings/ml?tab=training&horizon=7")).toBeInTheDocument();
  expect(load).toHaveBeenCalledTimes(2);
  consoleError.mockRestore();
});

it("keeps the loaded shell mounted across child route navigation", async () => {
  const mounted = vi.fn();
  function Shell() { mounted(); return <><Link to="/alerts">Alerts</Link><Outlet /></>; }
  const load=vi.fn().mockResolvedValue({default:Shell});
  render(<MemoryRouter initialEntries={["/devices"]}><Routes><Route element={<RouteDelivery load={load} label="console" />}><Route path="/devices" element={<p>Devices page</p>} /><Route path="/alerts" element={<p>Alerts page</p>} /></Route></Routes></MemoryRouter>);
  await screen.findByText("Devices page");
  const initialRenders=mounted.mock.calls.length;
  await userEvent.click(screen.getByRole("link",{name:"Alerts"}));
  await screen.findByText("Alerts page");
  expect(load).toHaveBeenCalledTimes(1);
  expect(mounted).toHaveBeenCalledTimes(initialRenders);
});
