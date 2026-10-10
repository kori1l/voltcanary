import {act,cleanup,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {TelemetryChart} from "./TelemetryChart";
afterEach(()=>{cleanup();vi.unstubAllGlobals();});
it("fills resized panels without stretching labels or bridging missing samples",()=>{
 let resize:ResizeObserverCallback=()=>{};const disconnect=vi.fn();
 vi.stubGlobal("ResizeObserver",class {constructor(callback:ResizeObserverCallback){resize=callback;}observe(){}disconnect=disconnect;});
 const from="2026-10-10T00:00:00Z",to="2026-10-10T01:00:00Z";
 render(<TelemetryChart fillContainer showSummary={false} series={[{metricKey:"temperatureC",timestamp:from,value:10},{metricKey:"temperatureC",timestamp:"2026-10-10T00:30:00Z",value:null},{metricKey:"temperatureC",timestamp:to,value:20}]} label="Temperature" unit="C" range={{from,to,preset:"custom"}} showPoints maxDisplayPoints={400}/>);
 const chart=screen.getByRole("img",{name:"Temperature chart"});
 const measure=(width:number,height:number)=>act(()=>resize([{contentRect:{width,height}} as ResizeObserverEntry],{} as ResizeObserver));
 measure(300,600);expect(chart).toHaveAttribute("viewBox","0 0 300 600");expect(chart.querySelectorAll("polyline")).toHaveLength(2);
 const plot=chart.querySelector(".chart-selection-hit-area")!;expect(Number(plot.getAttribute("height"))).toBeGreaterThan(500);
 measure(220,80);expect(chart).toHaveAttribute("viewBox","0 0 220 80");expect(Number(plot.getAttribute("height"))).toBeGreaterThan(0);
 cleanup();expect(disconnect).toHaveBeenCalled();
});
