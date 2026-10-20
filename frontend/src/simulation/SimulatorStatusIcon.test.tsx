import {cleanup,render,screen} from "@testing-library/react";
import {afterEach,expect,it} from "vitest";
import {SimulatorStatusIcon} from "./SimulatorStatusIcon";
afterEach(cleanup);
it("distinguishes checked service availability from unchecked state and runtime execution",()=>{
 const view=render(<SimulatorStatusIcon resource={{status:"idle",error:null,refreshing:false}}/>);expect(screen.getByRole("img",{name:"Simulator status not checked"})).toBeVisible();
 view.rerender(<SimulatorStatusIcon resource={{status:"error",error:"Service unavailable",refreshing:false}}/>);expect(screen.getByRole("img",{name:"Simulator unavailable"})).toHaveAttribute("title","Simulator unavailable: Service unavailable");
 view.rerender(<SimulatorStatusIcon resource={{status:"ready",error:null,refreshing:false}}/>);expect(screen.getByRole("img",{name:"Simulator available"})).toBeVisible();expect(screen.queryByText("Running")).not.toBeInTheDocument();
});
