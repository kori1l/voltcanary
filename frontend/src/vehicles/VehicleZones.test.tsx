import {render,screen,fireEvent,waitFor} from "@testing-library/react";
import {vi,it,expect} from "vitest";
import {VehicleZones} from "./VehicleZones";
import {defaultMonitoring} from "./monitoringModel";
const save=vi.hoisted(()=>vi.fn());
vi.mock("./monitoringApi",()=>({saveMonitoring:save}));
vi.mock("./VehicleMap",()=>({VehicleMap:()=>null}));
it("keeps zone edits local until explicit save and persists the bounded settings",async()=>{
  save.mockImplementation(async(_device,value)=>value);const onSave=vi.fn();
  render(<VehicleZones deviceId="car" settings={defaultMonitoring} onSave={onSave} evidence={null}/>);
  fireEvent.click(screen.getByRole("button",{name:"Add zone"}));expect(save).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("Radius (metres)"),{target:{value:"500"}});
  fireEvent.click(screen.getByRole("button",{name:"Save monitoring settings"}));
  await waitFor(()=>expect(onSave).toHaveBeenCalled());expect(save.mock.calls[0][1].zones[0].radiusMetres).toBe(500);
});
