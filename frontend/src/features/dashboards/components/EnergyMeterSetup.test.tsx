import {cleanup,render,screen,waitFor} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {MemoryRouter} from "react-router-dom";
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {listDataSources} from "../../../devices/devicesApi";
import {getEnergyMeter,saveEnergyMeter,type EnergyMeterDefinition} from "../model/energyMetersApi";
import {EnergyMeterSetup} from "./EnergyMeterSetup";
vi.mock("../../../devices/devicesApi",()=>({listDataSources:vi.fn()}));
vi.mock("../model/energyMetersApi",()=>({getEnergyMeter:vi.fn(),saveEnergyMeter:vi.fn()}));
vi.mock("../model/dashboardEntityOptions",()=>({dashboardDeviceOptions:async()=>[]}));
const metadata:EnergyMeterDefinition={revision:1,physicalMeterId:"physical",parentPhysicalMeterId:null,sourceId:"registered-source",authoritativeMethod:"COUNTER_DELTA",maxGapSeconds:900,effectiveFrom:"2026-10-08T00:00:00Z",effectiveTo:null,counterEvents:[],ancestorPhysicalMeterIds:[],topologyComplete:true};
beforeEach(()=>{vi.resetAllMocks();vi.mocked(listDataSources).mockResolvedValue([{id:"registered-source",deviceId:"owned",externalId:"Kitchen meter",protocol:"REST",sourceType:"SMART_PLUG"}]);});
afterEach(cleanup);
it("saves an explicit owner meter revision using a registered source and renewed optimistic revision",async()=>{
  vi.mocked(getEnergyMeter).mockResolvedValueOnce([]).mockResolvedValue([metadata]);vi.mocked(saveEnergyMeter).mockResolvedValue(metadata);
  render(<MemoryRouter><EnergyMeterSetup deviceId="owned"/></MemoryRouter>);await userEvent.click(screen.getByText("Physical meter and calculation",{exact:true}));
  await waitFor(()=>expect(screen.getByRole("button",{name:"Save meter revision"})).toBeEnabled());
  await userEvent.selectOptions(screen.getByRole("combobox",{name:"Energy calculation"}),"POWER_INTEGRAL");await userEvent.click(screen.getByRole("button",{name:"Save meter revision"}));
  await screen.findByText(/Meter revision 1 saved/);const [device,body]=vi.mocked(saveEnergyMeter).mock.calls[0];expect(device).toBe("owned");expect(body).toMatchObject({expectedRevision:0,sourceId:"registered-source",authoritativeMethod:"POWER_INTEGRAL",maxGapSeconds:900});expect(body.physicalMeterId).toMatch(/^[a-f0-9-]{36}$/);expect(vi.mocked(getEnergyMeter).mock.calls.every(call=>call[0]==="owned")).toBe(true);
});
it("does not allow saving an unavailable source or turn a failed metadata read into configuration",async()=>{
  vi.mocked(listDataSources).mockResolvedValue([]);vi.mocked(getEnergyMeter).mockRejectedValue(new Error("Access unavailable"));
  render(<MemoryRouter><EnergyMeterSetup deviceId="owned"/></MemoryRouter>);await userEvent.click(screen.getByText("Physical meter and calculation",{exact:true}));await screen.findByRole("alert");
  expect(screen.getByRole("button",{name:"Save meter revision"})).toBeDisabled();expect(saveEnergyMeter).not.toHaveBeenCalled();
});
