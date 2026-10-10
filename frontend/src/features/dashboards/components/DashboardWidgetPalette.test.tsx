import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import type {WidgetManifest} from "../../../dashboards/dashboardQueriesApi";
import {DashboardWidgetPalette,widgetDragMime} from "./DashboardWidgetPalette";
const manifests=[{type:"time-series",typeVersion:1,title:"Measured time series",supported:true},{type:"estimated-cost",typeVersion:1,title:"Estimated cost",supported:false,dependency:"Tariffs required"}] as WidgetManifest[];
afterEach(cleanup);
it("supports visual search, keyboard addition and a typed drag payload without adding on drag start",()=>{
 const add=vi.fn(),drag=vi.fn();render(<DashboardWidgetPalette manifests={manifests} disabled={false} limitReached={false} onClose={()=>{}} onAdd={add} onDragType={drag}/>);
 expect(screen.getByText("Unavailable: Tariffs required")).toBeVisible();
 fireEvent.change(screen.getByRole("searchbox",{name:"Search widgets"}),{target:{value:"temperature"}});expect(screen.queryByText("Estimated cost")).not.toBeInTheDocument();
 const setData=vi.fn(),dataTransfer={setData,effectAllowed:""};fireEvent.dragStart(screen.getByLabelText("Time series widget"),{dataTransfer});expect(setData).toHaveBeenCalledWith(widgetDragMime,"time-series");expect(add).not.toHaveBeenCalled();expect(drag).toHaveBeenCalledWith("time-series");
 fireEvent.dragEnd(screen.getByLabelText("Time series widget"));expect(drag).toHaveBeenLastCalledWith(null);
 fireEvent.click(screen.getByRole("button",{name:"Add Time series"}));expect(add).toHaveBeenCalledWith("time-series");
});
it("makes the definition limit explicit and prevents adding or dragging",()=>{
 const add=vi.fn(),drag=vi.fn();render(<DashboardWidgetPalette manifests={manifests} disabled={false} limitReached onClose={()=>{}} onAdd={add} onDragType={drag}/>);
 expect(screen.getByRole("status")).toHaveTextContent("24 widgets maximum");expect(screen.getByRole("button",{name:"Add Time series"})).toBeDisabled();expect(screen.getByLabelText("Time series widget")).toHaveAttribute("draggable","false");
 fireEvent.dragStart(screen.getByLabelText("Time series widget"),{dataTransfer:{setData:vi.fn()}});expect(drag).not.toHaveBeenCalled();
});
