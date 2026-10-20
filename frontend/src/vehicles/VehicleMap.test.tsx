import {render,screen,waitFor,cleanup} from "@testing-library/react";
import {afterEach,it,expect,vi} from "vitest";
import {VehicleMap} from "./VehicleMap";
import type {PositionEvidence} from "./positionApi";

vi.mock("maplibre-gl",()=>({setWorkerUrl:vi.fn(),Map:class{constructor(){throw new Error("WebGL unavailable");}}}));
afterEach(cleanup);
const evidence:PositionEvidence={latest:{deviceId:"car",sourceId:"gps",asOf:"2026-10-11T10:01:00Z",freshness:"STALE",position:{recordedAt:"2026-10-11T10:00:00Z",receivedAt:"2026-10-11T10:00:01Z",latitude:44.8,longitude:20.4,speedKmh:0,headingDeg:0,quality:["OK"]}},history:{deviceId:"car",sourceId:"gps",from:"2026-10-11T09:45:00Z",to:"2026-10-11T10:01:00Z",points:[],returnedCount:0,truncated:true}};
it("keeps textual GPS evidence and explicit stale/truncated states when WebGL fails",async()=>{
  render(<VehicleMap evidence={evidence}/>);
  await waitFor(()=>expect(screen.getByRole("alert")).toHaveTextContent("Map unavailable"));
  expect(screen.getByText("44.80000, 20.40000")).toBeInTheDocument();
  expect(screen.getByText("Speed: 0.0 km/h")).toBeInTheDocument();
  expect(screen.getByText("Stale position")).toBeInTheDocument();
  expect(screen.getByText(/Latest 1000/)).toBeInTheDocument();
});
it("does not invent a position while waiting for its first sample",()=>{
  render(<VehicleMap evidence={null}/>);
  expect(screen.getByRole("status")).toHaveTextContent("Waiting for GPS");
  expect(screen.queryByLabelText("Follow vehicle")).not.toBeInTheDocument();
});
