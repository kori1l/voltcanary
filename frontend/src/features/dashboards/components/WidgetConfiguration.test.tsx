import {cleanup,fireEvent,render,screen} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {WidgetConfiguration} from "./WidgetConfiguration";
import type {WidgetManifest} from "../../../dashboards/dashboardQueriesApi";
vi.mock("../model/dashboardEntityOptions",()=>({dashboardDeviceOptions:vi.fn().mockResolvedValue({items:[],hasNext:false,nextCursor:null})}));
const widget={id:"panel",title:"Equipment",type:"fleet-count",typeVersion:1,binding:{mode:"DYNAMIC" as const},query:{},presentation:{},navigation:{},payload:{}};
const manifest={type:"fleet-count",typeVersion:1,supported:true,reducers:[],timeBasis:"SNAPSHOT"} as unknown as WidgetManifest;
afterEach(cleanup);
it("retains multiple business bindings, bounds rows and blocks unfinished selections",()=>{
 const save=vi.fn();render(<WidgetConfiguration widget={{...widget,type:"risk-list",binding:{mode:"FIXED",deviceIds:["first","second"]}}} manifest={{...manifest,type:"risk-list"}} onSave={save} onCancel={()=>{}}/>);
 fireEvent.click(screen.getByRole("button",{name:"Add selected equipment"}));expect(screen.getByRole("button",{name:"Apply changes"})).toBeDisabled();fireEvent.click(screen.getByRole("button",{name:"Remove equipment 3"}));
 fireEvent.click(screen.getByRole("button",{name:"Calculation"}));fireEvent.change(screen.getByRole("spinbutton",{name:"Maximum displayed rows"}),{target:{value:"100"}});fireEvent.click(screen.getByRole("button",{name:"Appearance"}));fireEvent.click(screen.getByRole("checkbox",{name:"Show record timestamps"}));fireEvent.click(screen.getByRole("button",{name:"Apply changes"}));expect(save).toHaveBeenCalledWith(expect.objectContaining({binding:{mode:"FIXED",deviceIds:["first","second"]},query:expect.objectContaining({rows:50}),presentation:{showRecordTime:false}}));
});
it("saves prediction columns, page ordering and ML workspace navigation",()=>{
 const save=vi.fn();render(<WidgetConfiguration widget={{...widget,type:"prediction-evidence"}} manifest={{...manifest,type:"prediction-evidence"}} onSave={save} onCancel={()=>{}}/>);
 fireEvent.click(screen.getByRole("button",{name:"Appearance"}));fireEvent.change(screen.getByRole("combobox",{name:"Prediction order"}),{target:{value:"RISK_DESC"}});fireEvent.click(screen.getByRole("checkbox",{name:"Show model name and version"}));fireEvent.click(screen.getByRole("checkbox",{name:"Show calculation time"}));
 fireEvent.click(screen.getByRole("button",{name:"Navigation"}));fireEvent.change(screen.getByRole("combobox",{name:"Destination"}),{target:{value:"/settings/ml"}});fireEvent.click(screen.getByRole("button",{name:"Apply changes"}));expect(save).toHaveBeenCalledWith(expect.objectContaining({presentation:expect.objectContaining({predictionSort:"RISK_DESC",showPredictionModel:true,showPredictionTime:true}),navigation:{href:"/settings/ml"}}));
});
it("preserves pending data edits across Layout and does not submit data for a layout action",()=>{
 const save=vi.fn(),action=vi.fn();render(<WidgetConfiguration widget={widget} manifest={manifest} onSave={save} onCancel={()=>{}} layoutControls={<button type="button" onClick={action}>Duplicate panel</button>}/>);
 fireEvent.change(screen.getByRole("textbox",{name:/Widget title/}),{target:{value:"Changed equipment"}});fireEvent.click(screen.getByRole("button",{name:"Layout"}));
 expect(screen.queryByRole("button",{name:"Apply changes"})).not.toBeInTheDocument();fireEvent.click(screen.getByRole("button",{name:"Duplicate panel"}));expect(action).toHaveBeenCalledOnce();expect(save).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole("button",{name:"Data"}));expect(screen.getByRole("textbox",{name:/Widget title/})).toHaveValue("Changed equipment");fireEvent.click(screen.getByRole("button",{name:"Apply changes"}));expect(save).toHaveBeenCalledWith(expect.objectContaining({title:"Changed equipment"}));
});
it("keeps unsupported data inert while permitting layout management",()=>{
 render(<WidgetConfiguration widget={widget} manifest={{...manifest,supported:false}} onSave={vi.fn()} onCancel={()=>{}} layoutControls={<button type="button">Delete panel</button>}/>);
 expect(screen.getByRole("button",{name:"Delete panel"})).toBeVisible();expect(screen.queryByRole("button",{name:"Data"})).not.toBeInTheDocument();expect(screen.queryByRole("button",{name:"Apply changes"})).not.toBeInTheDocument();
});
