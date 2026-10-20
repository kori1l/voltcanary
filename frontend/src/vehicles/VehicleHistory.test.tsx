import {render,screen,waitFor,fireEvent} from "@testing-library/react";
import {vi,it,expect} from "vitest";
import {VehicleHistory} from "./VehicleHistory";
import {defaultMonitoring} from "./monitoringModel";
const load=vi.hoisted(()=>vi.fn());
vi.mock("./monitoringApi",()=>({recordedPositions:load}));
vi.mock("./VehicleMap",()=>({VehicleMap:({evidence}:{evidence:{latest:{position:{longitude:number}}}|null})=><p>Playback longitude {evidence?.latest.position.longitude}</p>}));
it("loads recordings explicitly, seeks accepted samples and aborts on unmount",async()=>{
  const points=[0,1,2].map(i=>({recordedAt:new Date(Date.UTC(2026,0,1)+i*5000).toISOString(),receivedAt:"",latitude:0,longitude:i*0.0001,speedKmh:36,headingDeg:0,quality:[]}));
  load.mockResolvedValue({points,truncated:false});
  const view=render(<VehicleHistory target={{deviceId:"car",sourceId:"gps"}} settings={defaultMonitoring}/>);
  expect(load).not.toHaveBeenCalled();fireEvent.click(screen.getByRole("button",{name:"Load recordings"}));
  await waitFor(()=>expect(screen.getByText("1 / 3 recorded samples")).toBeInTheDocument());
  fireEvent.change(screen.getByRole("slider",{name:"Recorded time"}),{target:{value:"2"}});
  expect(screen.getByText("Playback longitude 0.0002")).toBeInTheDocument();
  expect(screen.getByRole("button",{name:"Export CSV"})).toBeEnabled();
  const signal=load.mock.calls[0][3] as AbortSignal;view.unmount();expect(signal.aborted).toBe(true);
});
