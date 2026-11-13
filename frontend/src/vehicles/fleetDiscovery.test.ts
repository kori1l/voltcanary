import {beforeEach,expect,it,vi} from "vitest";
import {listDevices,type Device} from "../devices/devicesApi";
import {discoverFleet} from "./fleetDiscovery";

vi.mock("../devices/devicesApi",()=>({listDevices:vi.fn()}));
const list=vi.mocked(listDevices);
const device=(id:string,status:Device["status"]="ACTIVE"):Device=>({id,status,name:id,type:"VEHICLE",locationId:null,manufacturer:null,model:null});
beforeEach(()=>vi.resetAllMocks());

it("finds a real bus after a full page of archived test vehicles",async()=>{
  list.mockResolvedValueOnce(Array.from({length:100},(_,i)=>device(`old-${i}`,"DECOMMISSIONED")))
    .mockResolvedValueOnce([device("0012-02512"),device("offline","OFFLINE"),device("disabled","DISABLED")]);
  const signal=new AbortController().signal;
  expect(await discoverFleet(signal)).toEqual({items:[device("0012-02512"),device("offline","OFFLINE"),device("disabled","DISABLED")],capped:false});
  expect(list).toHaveBeenNthCalledWith(2,{type:"VEHICLE",limit:100,offset:100},signal);
});

it("caps eligible vehicles at 100 and detects additional vehicles on the next page",async()=>{
  list.mockResolvedValueOnce(Array.from({length:100},(_,i)=>device(`bus-${i}`))).mockResolvedValueOnce([device("extra")]);
  const result=await discoverFleet(new AbortController().signal);
  expect(result.items).toHaveLength(100);expect(result.capped).toBe(true);
});

it("does not report a partial fleet as complete after a later page fails",async()=>{
  list.mockResolvedValueOnce(Array.from({length:100},(_,i)=>device(`bus-${i}`))).mockRejectedValueOnce(new Error("inventory unavailable"));
  await expect(discoverFleet(new AbortController().signal)).rejects.toThrow("inventory unavailable");
});

it("stops loading pages when the console leaves discovery",async()=>{
  const controller=new AbortController();
  list.mockImplementationOnce(async()=>{controller.abort();return Array.from({length:100},(_,i)=>device(`old-${i}`,"DECOMMISSIONED"));});
  await expect(discoverFleet(controller.signal)).rejects.toThrow();
  expect(list).toHaveBeenCalledTimes(1);
});
