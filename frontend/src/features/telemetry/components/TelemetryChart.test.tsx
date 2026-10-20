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

it("shows true/false steps and never bridges a missing state",()=>{
 const from="2026-10-10T00:00:00Z",to="2026-10-10T00:00:20Z";
 const samples=[{timestamp:from,value:0},{timestamp:"2026-10-10T00:00:05Z",value:1},{timestamp:"2026-10-10T00:00:10Z",value:null},{timestamp:to,value:0}].map(point=>({...point,metricKey:"doors"}));
 render(<TelemetryChart series={samples} label="Doors open" unit="boolean" range={{from,to,preset:"custom"}} showPoints maxDisplayPoints={400}/>);
 const chart=screen.getByRole("img",{name:"Doors open chart"});
 expect(chart).toHaveTextContent("False");expect(chart).toHaveTextContent("True");
 const lines=chart.querySelectorAll("polyline");expect(lines).toHaveLength(2);
 const step=lines[0].getAttribute("points")!.split(" ");expect(step).toHaveLength(3);
 expect(step[0].split(',')[1]).toBe(step[1].split(',')[1]);expect(step[1].split(',')[0]).toBe(step[2].split(',')[0]);
});
