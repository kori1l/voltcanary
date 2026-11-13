import {render,screen,fireEvent} from "@testing-library/react";
import {it,expect,vi} from "vitest";
import {DeviceSetupResult} from "./DeviceSetupResult";
import {readDeviceSetup} from "./deviceSetupApi";
vi.mock("./deviceSetupApi",()=>({readDeviceSetup:vi.fn()}));

it("does not claim live data until the authorized status read confirms fresh evidence",async()=>{
  vi.mocked(readDeviceSetup).mockResolvedValue({deviceId:"bus",sourceId:"hsl-bus",setupStatus:"TELEMETRY_READY",freshness:"FRESH"});
  render(<DeviceSetupResult deviceId="bus" initialStatus="WAITING_FOR_DATA"/>);
  expect(screen.getByText(/передача телеметрии пока не подтверждена/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button",{name:"Проверить поступление данных"}));
  expect(await screen.findByText(/Получена свежая реальная телеметрия/)).toBeInTheDocument();
  expect(readDeviceSetup).toHaveBeenCalledWith("bus",expect.any(AbortSignal));
});
