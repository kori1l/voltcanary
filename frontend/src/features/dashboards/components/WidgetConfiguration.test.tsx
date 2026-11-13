import {cleanup,fireEvent,render,screen,waitFor} from "@testing-library/react";
import {afterEach,expect,it,vi} from "vitest";
import {WidgetConfiguration} from "./WidgetConfiguration";
import type {WidgetManifest,WidgetPresentationSetting} from "../../../dashboards/dashboardQueriesApi";
vi.mock("../model/dashboardEntityOptions",()=>({vehicleDeviceOptions:vi.fn().mockResolvedValue({items:[],hasNext:false,nextCursor:null}),dashboardDeviceOptions:vi.fn().mockResolvedValue({items:[],hasNext:false,nextCursor:null})}));
vi.mock("../../../vehicles/VehicleSourceSelector",()=>({VehicleSourceSelector:()=>null}));
vi.mock("../../../vehicles/MapFieldSelection",()=>({MapFieldSelection:()=>null}));
vi.mock("../../../telemetry/telemetryApi",()=>({getTelemetryMetrics:vi.fn().mockResolvedValue([{name:"temperatureC",unit:"C"}])}));
const widget={id:"panel",title:"Equipment",type:"fleet-count",typeVersion:1,binding:{mode:"DYNAMIC" as const},query:{},presentation:{},navigation:{},payload:{}};
const manifest={type:"fleet-count",typeVersion:1,supported:true,reducers:[],timeBasis:"SNAPSHOT"} as unknown as WidgetManifest;
const flag=(key:string,label:string,fallback:boolean):WidgetPresentationSetting=>({key,label,type:"BOOLEAN",section:"APPEARANCE",group:"Details",defaultValue:fallback,choices:[],maxLength:null});
function manifestFor(type:string):WidgetManifest {
 const settings:WidgetPresentationSetting[]=type==="risk-list"?[flag("showRecordTime","Show record timestamps",true)]:type==="vehicle-map"?[flag("showMapDetails","Show map controls and GPS/journey details",true),flag("showMapNotices","Show stale data and truncated trail notices below map",true)]:type==="prediction-evidence"?[{key:"predictionSort",label:"Prediction order",type:"SELECT",section:"APPEARANCE",group:"Predictions",defaultValue:"RECENT",choices:[{value:"RECENT",label:"Most recently calculated"},{value:"RISK_DESC",label:"Highest risk"}],maxLength:null},flag("showPredictionModel","Show model name and version",false),flag("showPredictionTime","Show calculation time",false)]:[{key:"compact",label:"Value card layout",type:"AUTO_BOOLEAN",section:"APPEARANCE",group:"Value",defaultValue:null,choices:[{value:"auto",label:"Automatic"},{value:"true",label:"Compact"},{value:"false",label:"Full"}],maxLength:null}];
 settings.unshift({key:"showTitle",label:"Show panel title",type:"BOOLEAN",section:"GENERAL",group:"Header",defaultValue:false,choices:[],maxLength:null});
 return {...manifest,type,reducers:type==="latest-value"?["RAW"]:[],capabilities:{version:1,dataKind:"DOMAIN_EVIDENCE",valueTypes:[],binding:{modes:[],maxDevices:20,sourceRequired:type==="vehicle-map",metricRequired:type==="latest-value"},visualizations:[],settings}};
}
afterEach(cleanup);
it("retains multiple business bindings, bounds rows and blocks unfinished selections",()=>{
 const save=vi.fn();render(<WidgetConfiguration widget={{...widget,type:"risk-list",binding:{mode:"FIXED",deviceIds:["first","second"]}}} manifest={manifestFor("risk-list")} onSave={save} onCancel={()=>{}}/>);
 fireEvent.click(screen.getByRole("button",{name:"Data"}));fireEvent.click(screen.getByRole("button",{name:"Add selected equipment"}));expect(screen.getByRole("button",{name:"Apply changes"})).toBeDisabled();fireEvent.click(screen.getByRole("button",{name:"Remove equipment 3"}));
 fireEvent.click(screen.getByRole("button",{name:"Calculation"}));fireEvent.change(screen.getByRole("spinbutton",{name:"Maximum displayed rows"}),{target:{value:"100"}});fireEvent.click(screen.getByRole("button",{name:"Appearance"}));fireEvent.click(screen.getByRole("checkbox",{name:"Show record timestamps"}));fireEvent.click(screen.getByRole("button",{name:"Apply changes"}));expect(save).toHaveBeenCalledWith(expect.objectContaining({binding:{mode:"FIXED",deviceIds:["first","second"]},query:expect.objectContaining({rows:50}),presentation:{showRecordTime:false}}));
});
it("saves prediction columns, page ordering and ML workspace navigation",()=>{
 const save=vi.fn();render(<WidgetConfiguration widget={{...widget,type:"prediction-evidence"}} manifest={manifestFor("prediction-evidence")} onSave={save} onCancel={()=>{}}/>);
 fireEvent.click(screen.getByRole("button",{name:"Appearance"}));fireEvent.change(screen.getByRole("combobox",{name:"Prediction order"}),{target:{value:"RISK_DESC"}});fireEvent.click(screen.getByRole("checkbox",{name:"Show model name and version"}));fireEvent.click(screen.getByRole("checkbox",{name:"Show calculation time"}));
 fireEvent.click(screen.getByRole("button",{name:"Navigation"}));fireEvent.change(screen.getByRole("combobox",{name:"Destination"}),{target:{value:"/settings/ml"}});fireEvent.click(screen.getByRole("button",{name:"Apply changes"}));expect(save).toHaveBeenCalledWith(expect.objectContaining({presentation:expect.objectContaining({predictionSort:"RISK_DESC",showPredictionModel:true,showPredictionTime:true}),navigation:{href:"/settings/ml"}}));
});
it("preserves pending data edits across Layout and does not submit data for a layout action",()=>{
 const save=vi.fn(),action=vi.fn();render(<WidgetConfiguration widget={widget} manifest={manifest} onSave={save} onCancel={()=>{}} layoutControls={<button type="button" onClick={action}>Duplicate panel</button>}/>);
 fireEvent.change(screen.getByRole("textbox",{name:/Widget title/}),{target:{value:"Changed equipment"}});fireEvent.click(screen.getByRole("button",{name:"Layout"}));
 expect(screen.queryByRole("button",{name:"Apply changes"})).not.toBeInTheDocument();fireEvent.click(screen.getByRole("button",{name:"Duplicate panel"}));expect(action).toHaveBeenCalledOnce();expect(save).not.toHaveBeenCalled();
 fireEvent.click(screen.getByRole("button",{name:"General"}));expect(screen.getByRole("textbox",{name:/Widget title/})).toHaveValue("Changed equipment");fireEvent.click(screen.getByRole("button",{name:"Apply changes"}));expect(save).toHaveBeenCalledWith(expect.objectContaining({title:"Changed equipment"}));
});
it("keeps unsupported data inert while permitting layout management",()=>{
 render(<WidgetConfiguration widget={widget} manifest={{...manifest,supported:false}} onSave={vi.fn()} onCancel={()=>{}} layoutControls={<button type="button">Delete panel</button>}/>);
 expect(screen.getByRole("button",{name:"Delete panel"})).toBeVisible();expect(screen.queryByRole("button",{name:"Data"})).not.toBeInTheDocument();expect(screen.queryByRole("button",{name:"Apply changes"})).not.toBeInTheDocument();
});

it("saves map title and GPS metadata visibility independently",()=>{
 const save=vi.fn();render(<WidgetConfiguration widget={{...widget,type:"vehicle-map",binding:{mode:"FIXED",deviceIds:["bus"]},query:{sourceId:"gps"},presentation:{showTitle:true}}} manifest={manifestFor("vehicle-map")} onSave={save} onCancel={()=>{}}/>);
 fireEvent.click(screen.getByRole("checkbox",{name:"Show panel title"}));
 fireEvent.click(screen.getByRole("button",{name:"Appearance"}));
 fireEvent.click(screen.getByRole("checkbox",{name:"Show map controls and GPS/journey details"}));
 fireEvent.click(screen.getByRole("checkbox",{name:"Show stale data and truncated trail notices below map"}));
 fireEvent.click(screen.getByRole("button",{name:"Apply changes"}));
 expect(save).toHaveBeenCalledWith(expect.objectContaining({presentation:{showTitle:false,showMapDetails:false,showMapNotices:false}}));
});

it("saves compact value layout independently of title visibility",async()=>{
 const save=vi.fn();render(<WidgetConfiguration widget={{...widget,type:"latest-value",binding:{mode:"FIXED",deviceIds:["device"]},query:{metric:"temperatureC",unit:"C"},presentation:{showTitle:true}}} manifest={manifestFor("latest-value")} onSave={save} onCancel={()=>{}}/>);
 fireEvent.click(screen.getByRole("button",{name:"Appearance"}));
 await waitFor(()=>expect(screen.getByRole("button",{name:"Apply changes"})).toBeEnabled());
 fireEvent.change(screen.getByRole("combobox",{name:"Value card layout"}),{target:{value:"true"}});
 fireEvent.click(screen.getByRole("button",{name:"Apply changes"}));
 expect(save).toHaveBeenCalledWith(expect.objectContaining({presentation:{showTitle:true,compact:true}}));
});
