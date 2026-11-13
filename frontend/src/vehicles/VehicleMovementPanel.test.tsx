import {cleanup, render, screen, waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {MemoryRouter} from "react-router-dom";
import {afterEach, expect, it, vi} from "vitest";
import {VehicleMovementPanel} from "./VehicleMovementPanel";
import {listDataSources} from "../devices/devicesApi";
import {getPositionEvidence} from "./positionApi";
vi.mock("../devices/devicesApi",()=>({listDataSources:vi.fn()}));
vi.mock("./positionApi",()=>({getPositionEvidence:vi.fn()}));
vi.mock("./VehicleMap",()=>({VehicleMap:()=> <p>Accepted GPS map</p>}));
afterEach(()=>{cleanup();vi.clearAllMocks();});
const source=(id:string,sourceType:string,protocol="REST")=>({id,externalId:id,sourceType,protocol}) as Awaited<ReturnType<typeof listDataSources>>[number];
it("reads a real API GPS source and routes setup to Configuration",async()=>{
  vi.mocked(listDataSources).mockResolvedValue([source("hsl-bus","API")]);
  vi.mocked(getPositionEvidence).mockResolvedValue({} as Awaited<ReturnType<typeof getPositionEvidence>>);
  render(<MemoryRouter><VehicleMovementPanel deviceId="bus"/></MemoryRouter>);
  await waitFor(()=>expect(getPositionEvidence).toHaveBeenCalledWith("bus","hsl-bus",expect.any(String),expect.any(String),expect.any(AbortSignal)));
  expect(screen.getByRole("link",{name:"Configure sources"})).toHaveAttribute("href","/devices/bus?tab=configuration");
  expect(screen.queryByRole("link",{name:"Open Demo Tools"})).not.toBeInTheDocument();
});
it("keeps simulator navigation for an explicitly bound simulator source",async()=>{
  vi.mocked(listDataSources).mockResolvedValue([source("sim-car","SIMULATOR")]);
  render(<MemoryRouter><VehicleMovementPanel deviceId="car"/></MemoryRouter>);
  expect(await screen.findByRole("link",{name:"Open Demo Tools"})).toHaveAttribute("href","/devices/car?tab=simulation");
});
it("requires explicit selection for multiple REST sources and excludes other protocols",async()=>{
  vi.mocked(listDataSources).mockResolvedValue([source("api","API"),source("sensor","SENSOR"),source("mqtt","API","MQTT")]);
  render(<MemoryRouter><VehicleMovementPanel deviceId="car"/></MemoryRouter>);
  await screen.findByRole("option",{name:"sensor"});
  expect(getPositionEvidence).not.toHaveBeenCalled();
  expect(screen.queryByRole("option",{name:"mqtt"})).not.toBeInTheDocument();
  await userEvent.selectOptions(screen.getByRole("combobox",{name:"GPS source"}),"sensor");
  await waitFor(()=>expect(getPositionEvidence).toHaveBeenCalledWith("car","sensor",expect.any(String),expect.any(String),expect.any(AbortSignal)));
});
