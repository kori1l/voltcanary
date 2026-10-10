import {render,screen,within,waitFor,cleanup} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {Link,MemoryRouter,Route,Routes} from "react-router-dom";
import {afterEach,beforeEach,expect,it,vi} from "vitest";
import {DashboardCatalog} from "./DashboardCatalog";
import {DashboardNavigation} from "./DashboardNavigation";
import {getDashboard,getDashboardPreferences,listDashboards,transitionDashboard} from "../../../dashboards/dashboardsApi";
import {readDashboardMenu,updateDashboardMenu} from "../model/dashboardMenuPreferences";
vi.mock("../../../dashboards/dashboardsApi",async(original)=>({...await original<typeof import("../../../dashboards/dashboardsApi")>(),listDashboards:vi.fn(),getDashboard:vi.fn(),getDashboardPreferences:vi.fn(),transitionDashboard:vi.fn()}));
const item={id:"monitor",name:"Monitoring",ownerUserId:"actor",accountId:"account",description:"",status:"ACTIVE" as const,publishedRevision:1,createdAt:"",updatedAt:""};
beforeEach(()=>{updateDashboardMenu("navigation-test",()=>({collapsed:false,hidden:[]}));vi.mocked(listDashboards).mockResolvedValue({items:[item],hasNext:false,nextCursor:null});vi.mocked(getDashboardPreferences).mockResolvedValue({defaultDashboardId:null,favorites:[],defaultContext:{},etag:"pref",updatedAt:null,fallbackReason:null});vi.mocked(getDashboard).mockResolvedValue({dashboard:item,definition:null,revision:1,draftEtag:"fresh",permissions:["edit","archive"],unsupportedWidgetIds:[]});});
afterEach(()=>{cleanup();vi.clearAllMocks();});
function setup(){render(<MemoryRouter><Link to="/dashboards">Dashboards</Link><DashboardNavigation actorId="navigation-test"/><Routes><Route path="/" element={null}/><Route path="/dashboard" element={null}/><Route path="/dashboards" element={<DashboardCatalog actorId="navigation-test"/>}/></Routes></MemoryRouter>);}
it("keeps sidebar links clean and manages group visibility in the catalog",async()=>{
 setup();await screen.findByRole("link",{name:"Monitoring"});expect(screen.queryByRole("button")).not.toBeInTheDocument();expect(screen.queryByRole("searchbox")).not.toBeInTheDocument();
 await userEvent.click(screen.getByRole("link",{name:"Dashboards"}));expect(screen.getByRole("heading",{name:"Dashboards"})).toBeVisible();await userEvent.click(screen.getByRole("button",{name:"Hide sidebar dashboards"}));expect(screen.queryByRole("navigation",{name:"Available dashboards"})).not.toBeInTheDocument();expect(readDashboardMenu("navigation-test").collapsed).toBe(true);
 await userEvent.click(screen.getByRole("button",{name:"Show sidebar dashboards"}));expect(screen.getByRole("navigation",{name:"Available dashboards"})).toBeVisible();expect(screen.getByRole("button",{name:"New dashboard"})).toBeInTheDocument();
});
it("hides only a menu entry and restores it from the Hidden list",async()=>{
 setup();await userEvent.click(screen.getByRole("link",{name:"Dashboards"}));await userEvent.click(await screen.findByRole("button",{name:"Actions for Monitoring"}));await screen.findByRole("link",{name:"Edit dashboard"});await userEvent.click(screen.getByRole("button",{name:"Hide from menu"}));expect(readDashboardMenu("navigation-test").hidden).toEqual(["monitor"]);expect(transitionDashboard).not.toHaveBeenCalled();
 await userEvent.click(screen.getByRole("button",{name:"Close"}));expect(within(screen.getByRole("navigation",{name:"Available dashboards"})).queryByRole("link",{name:"Monitoring"})).not.toBeInTheDocument();await userEvent.click(screen.getByRole("button",{name:"Hidden"}));const list=await screen.findByRole("list");await userEvent.click(await within(list).findByRole("button",{name:"Actions for Monitoring"}));await userEvent.click(screen.getByRole("button",{name:"Show in menu"}));expect(readDashboardMenu("navigation-test").hidden).toEqual([]);
});
it("requires archive confirmation and uses the fetched etag",async()=>{
 vi.mocked(transitionDashboard).mockResolvedValue({...item,status:"ARCHIVED"});setup();await userEvent.click(screen.getByRole("link",{name:"Dashboards"}));await userEvent.click(await screen.findByRole("button",{name:"Actions for Monitoring"}));await userEvent.click(await screen.findByRole("button",{name:"Archive dashboard"}));expect(transitionDashboard).not.toHaveBeenCalled();await userEvent.click(screen.getByRole("button",{name:"Confirm archive"}));await waitFor(()=>expect(transitionDashboard).toHaveBeenCalledWith("monitor","fresh",true));
});
it("isolates menu choices between actors and persists without server mutations",()=>{
 updateDashboardMenu("actor-a",()=>({collapsed:true,hidden:["a"]}));updateDashboardMenu("actor-b",()=>({collapsed:false,hidden:[]}));expect(readDashboardMenu("actor-b")).toEqual({collapsed:false,hidden:[]});expect(JSON.parse(localStorage.getItem("voltcanary.dashboard-menu.actor-a")!)).toEqual({collapsed:true,hidden:["a"]});
});
