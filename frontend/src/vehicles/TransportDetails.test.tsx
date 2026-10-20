import {render,screen} from "@testing-library/react";
import {it,expect} from "vitest";
import {TransportDetails} from "./TransportDetails";
import type {TransportContext} from "./positionApi";
it("shows observed false/zero values without inventing unavailable fields",()=>{
  render(<TransportDetails context={{routeShortName:"3",destination:"Käpylä",doorsOpen:false,scheduleDeviationSeconds:0} as TransportContext}/>);
  expect(screen.getByText("Closed")).toBeInTheDocument();
  expect(screen.getByText("0 s")).toBeInTheDocument();
  expect(screen.getByText("Käpylä")).toBeInTheDocument();
  expect(screen.queryByText("Next stop ID")).not.toBeInTheDocument();
});
