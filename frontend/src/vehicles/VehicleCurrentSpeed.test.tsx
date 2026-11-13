import {cleanup,render,screen} from "@testing-library/react";
import {afterEach,expect,it} from "vitest";
import {VehicleCurrentSpeed} from "./VehicleCurrentSpeed";
import type {LatestPosition} from "./positionApi";

afterEach(cleanup);
const latest:LatestPosition={deviceId:"bus",sourceId:"hsl-bus",asOf:"2026-10-22T20:03:14Z",freshness:"FRESH",position:{latitude:60,longitude:24,speedKmh:0,headingDeg:0,recordedAt:"2026-10-22T20:03:10Z",receivedAt:"2026-10-22T20:03:11Z",quality:[]}};

it("shows genuine zero speed for a fresh stationary vehicle",()=>{
  render(<VehicleCurrentSpeed latest={latest}/>);
  expect(screen.getByText("Скорость: 0 km/h")).toBeInTheDocument();
});
it("hides an old numeric speed when the owner reports stale measurements",()=>{
  render(<VehicleCurrentSpeed latest={{...latest,freshness:"STALE",position:{...latest.position!,speedKmh:44}}}/>);
  expect(screen.getByText("Текущая скорость недоступна")).toBeInTheDocument();
  expect(screen.queryByText(/44/)).not.toBeInTheDocument();
});
it("does not present a retained fresh snapshot as current after a failed refresh",()=>{
  render(<VehicleCurrentSpeed latest={latest} unavailable/>);
  expect(screen.getByText("Текущая скорость недоступна")).toBeInTheDocument();
  expect(screen.queryByText(/km\/h/)).not.toBeInTheDocument();
});
