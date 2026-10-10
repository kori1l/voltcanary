import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {DashboardPanelLayoutSettings} from "./DashboardPanelLayoutSettings";
import {blankDashboard,normalizeLayouts} from "../model/dashboardLayout";
const definition=normalizeLayouts({...blankDashboard("Layout"),widgets:["a","b"].map((id)=>({id,title:id,type:"fleet-count",typeVersion:1,binding:{mode:"ALL" as const},query:{},presentation:{},navigation:{},payload:{}}))});
afterEach(cleanup);
function setup(disabled=false,limitReached=false){const actions={onMove:vi.fn(),onResize:vi.fn(),onPlace:vi.fn(),onScreen:vi.fn(),onSection:vi.fn(),onDuplicate:vi.fn(),onDelete:vi.fn()};render(<DashboardPanelLayoutSettings definition={definition} id="a" breakpoint="desktop" screens={[{id:"main",title:"Monitoring",widgetIds:["a","b"]},{id:"analytics",title:"Analytics",widgetIds:[]}]} screenId="main" disabled={disabled} limitReached={limitReached} {...actions}/>);return actions;}
it("offers size, placement, screen transfer and management within settings",()=>{
 const a=setup();expect(screen.getByRole("button",{name:"Move left"})).toBeDisabled();
 fireEvent.change(screen.getByRole("combobox",{name:"Size"}),{target:{value:"wide"}});expect(a.onResize).toHaveBeenCalledWith(12,3);
 fireEvent.change(screen.getByRole("combobox",{name:"Place beside"}),{target:{value:"b"}});fireEvent.click(screen.getByRole("button",{name:"Move to selected position"}));expect(a.onPlace).toHaveBeenCalledWith("b","below");
 fireEvent.change(screen.getByRole("combobox",{name:"Screen"}),{target:{value:"analytics"}});expect(a.onScreen).toHaveBeenCalledWith("analytics");
 fireEvent.click(screen.getByRole("button",{name:"Duplicate panel"}));expect(a.onDuplicate).toHaveBeenCalledOnce();fireEvent.click(screen.getByRole("button",{name:"Delete panel"}));expect(a.onDelete).toHaveBeenCalledOnce();
});
it("retains lock and widget-limit restrictions",()=>{
 setup(true,true);expect(screen.getByRole("button",{name:"Duplicate panel"})).toBeDisabled();expect(screen.getByRole("button",{name:"Delete panel"})).toBeDisabled();expect(screen.getByRole("combobox",{name:"Screen"})).toBeDisabled();
});

it("accepts compact exact dimensions",()=>{
 const actions=setup();fireEvent.change(screen.getByRole("spinbutton",{name:"Width (columns)"}),{target:{value:"1"}});fireEvent.change(screen.getByRole("spinbutton",{name:"Height (rows)"}),{target:{value:"1"}});fireEvent.click(screen.getByRole("button",{name:"Apply panel size"}));expect(actions.onResize).toHaveBeenCalledWith(1,1);
});
