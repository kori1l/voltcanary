import {cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {MemoryRouter,Route,Routes} from "react-router-dom";
import {DashboardManagement} from "./DashboardManagement";
import {copyDashboard,getDashboardPreferences,saveDashboardPreferences} from "../../../dashboards/dashboardsApi";
vi.mock("../../../dashboards/dashboardsApi",async(original)=>({...await original<typeof import("../../../dashboards/dashboardsApi")>(),copyDashboard:vi.fn(),getDashboardPreferences:vi.fn(),saveDashboardPreferences:vi.fn()}));
afterEach(()=>{cleanup();vi.resetAllMocks();});
it("copies the concrete dashboard with the same idempotency key on retry",async()=>{
 vi.mocked(getDashboardPreferences).mockResolvedValue({defaultDashboardId:null,favorites:[],defaultContext:{},etag:"prefs",updatedAt:null,fallbackReason:null});
 vi.mocked(copyDashboard).mockRejectedValueOnce(new Error("Connection failed")).mockResolvedValueOnce({id:"copy",accountId:"account",ownerUserId:"owner",name:"Monitoring copy",description:"",status:"ACTIVE",publishedRevision:0,createdAt:"",updatedAt:""});
 render(<MemoryRouter><Routes><Route path="/" element={<DashboardManagement id="source" name="Monitoring" published/>}/><Route path="/dashboards/copy/edit" element={<h1>Copied editor</h1>}/></Routes></MemoryRouter>);
 fireEvent.click(screen.getByRole("button",{name:"Copy dashboard"}));expect(screen.getByText(/Copies the latest published revision/)).toBeInTheDocument();fireEvent.click(screen.getByRole("button",{name:"Create copy"}));await screen.findByRole("alert");fireEvent.click(screen.getByRole("button",{name:"Create copy"}));await screen.findByRole("heading",{name:"Copied editor"});const calls=vi.mocked(copyDashboard).mock.calls;expect(calls[0].slice(0,2)).toEqual(["source","Monitoring copy"]);expect(calls[1][2]).toBe(calls[0][2]);
});
it("updates favorites in settings while retaining default and runtime context",async()=>{
 const current={defaultDashboardId:"other",favorites:[],defaultContext:{},etag:"prefs",updatedAt:null,fallbackReason:null};vi.mocked(getDashboardPreferences).mockResolvedValue(current);vi.mocked(saveDashboardPreferences).mockResolvedValue({...current,favorites:["source"]});render(<MemoryRouter><DashboardManagement id="source" name="Monitoring" published/></MemoryRouter>);await waitFor(()=>expect(screen.getByRole("button",{name:"Add to favorites"})).toBeEnabled());fireEvent.click(screen.getByRole("button",{name:"Add to favorites"}));await screen.findByRole("button",{name:"Remove from favorites"});expect(saveDashboardPreferences).toHaveBeenCalledWith({...current,favorites:["source"]});
});
