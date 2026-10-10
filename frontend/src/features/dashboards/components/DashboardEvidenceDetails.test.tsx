import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it} from "vitest";
import {DashboardEvidenceDetails} from "./DashboardEvidenceDetails";
afterEach(cleanup);
it("keeps zero, unknown and private fields distinct in nested records",()=>{
 render(<DashboardEvidenceDetails value={{accountId:"private-account",value:0,missing:null,details:{quality:"OK",deviceId:"long-device-identifier"}}}/>);
 expect(screen.queryByText("private-account")).not.toBeInTheDocument();expect(screen.getByText("0")).toBeVisible();expect(screen.getByText("Unavailable")).toBeVisible();expect(screen.getByText("long-device-identifier")).toHaveClass("dashboard-info-value");expect(document.querySelector("table")).toBeNull();
});
it("explicitly pages long record lists and renders additional records on demand",()=>{
 render(<DashboardEvidenceDetails value={Array.from({length:25},(_,i)=>({name:`Equipment ${i+1}`,value:i}))}/>);
 expect(screen.getByText("25 entries · Showing 20")).toBeVisible();expect(screen.queryByText("Equipment 25")).not.toBeInTheDocument();fireEvent.click(screen.getByRole("button",{name:"Show more entries"}));expect(screen.getByText("Equipment 25",{selector:"summary"})).toBeVisible();expect(screen.queryByRole("button",{name:"Show more entries"})).not.toBeInTheDocument();
});
