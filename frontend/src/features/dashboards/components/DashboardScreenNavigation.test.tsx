import {useState} from "react";
import {cleanup,render,screen} from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {afterEach,expect,it} from "vitest";
import type {DashboardDefinition} from "../../../dashboards/dashboardsApi";
import {blankDashboard} from "../model/dashboardLayout";
import {dashboardScreens,screenDefinition} from "../model/dashboardScreens";
import {DashboardScreenNavigation} from "./DashboardScreenNavigation";
afterEach(cleanup);
it("creates three task screens, switches through an accessible screen selector and preserves widgets when removing a screen",async()=>{
  let current:DashboardDefinition=blankDashboard("Working screens");
  function Work(){const [definition,setDefinition]=useState(current),[active,setActive]=useState("main");current=definition;const selected=dashboardScreens(definition).find((screen)=>screen.id===active) ?? dashboardScreens(definition)[0];return <DashboardScreenNavigation definition={definition} activeId={selected.id} onEdit={setDefinition} onSelect={setActive}><p>Active work: {selected.title}</p><p>{screenDefinition(definition,selected.id).widgets.length} current widgets</p></DashboardScreenNavigation>;}
  render(<Work/>);
  for(const name of ["Monitoring","Analytics","Incidents"]){await userEvent.click(screen.getByRole("button",{name:"Add screen"}));await userEvent.selectOptions(screen.getByRole("combobox",{name:"Start this screen from"}),name);await userEvent.click(screen.getByRole("button",{name:"Create working screen"}));await screen.findByText(`Active work: ${name}`);}
  expect(dashboardScreens(current).map((screen)=>screen.title)).toEqual(["Monitoring","Analytics","Incidents"]);expect(new Set(current.screens!.flatMap((screen)=>screen.widgetIds)).size).toBe(current.widgets.length);
  await userEvent.selectOptions(screen.getByRole("combobox",{name:"Working screens"}),dashboardScreens(current).find((screen)=>screen.title==="Analytics")!.id);await screen.findByText("Active work: Analytics");const total=current.widgets.length;
  await userEvent.click(screen.getByRole("button",{name:"Screen settings"}));await userEvent.click(screen.getByRole("button",{name:"Remove screen"}));await userEvent.click(screen.getByRole("button",{name:"Remove screen and keep widgets"}));
  expect(current.widgets).toHaveLength(total);expect(dashboardScreens(current)).toHaveLength(2);expect(new Set(current.screens!.flatMap((screen)=>screen.widgetIds)).size).toBe(total);
});
