import {describe,expect,it} from "vitest";
import {instantiateTemplate,type DashboardTemplate} from "./dashboardTemplates";
const refrigeration:DashboardTemplate={id:"refrigeration",version:1,title:"Refrigeration",task:"Review equipment",persona:"Manager",segments:["Café"],available:true,explanation:"",period:"LAST_24_HOURS",seriesHours:24,slots:["primary"],panels:[
  {key:"temperature",type:"latest-value",title:"Temperature",slot:"primary",metric:"temperatureC",unit:"C",query:{reducer:"RAW"},drillDown:"/devices"},
  {key:"power",type:"time-series",title:"Power",slot:"primary",metric:"activePowerW",unit:"W",query:{reducer:"RAW"},drillDown:"/devices"}]};
describe("independent symbolic template snapshots",()=>{
  it("requires reviewed physical independence and saves it in the energy snapshot",()=>{
    const template={...refrigeration,id:"energy",panels:[{...refrigeration.panels[0],type:"energy-period",slot:"equipment",metric:null,unit:null}]};
    const binding={deviceIds:["one","two"],locationId:"",missingKeys:[],keepMissing:true,demo:false};
    expect(()=>instantiateTemplate(template,"Energy",binding)).toThrow("independent physical meter");
    const definition=instantiateTemplate(template,"Energy",{...binding,independentMetersConfirmed:true});
    expect(definition.widgets[0].query.independentMetersConfirmed).toBe(true);expect(definition.widgets[0].binding.deviceIds).toEqual(["one","two"]);
  });
  it("keeps unavailable temperature unbound or removes it while preserving measured power",()=>{
    const binding={deviceIds:["authorized"],locationId:"",missingKeys:["temperature"],keepMissing:true,demo:true};
    const kept=instantiateTemplate(refrigeration,"Cold room",binding);
    expect(kept.widgets[0].binding).toEqual({mode:"UNBOUND"});expect(kept.widgets[0].title).toContain("Measurement unavailable");
    expect(kept.widgets[1].binding.deviceIds).toEqual(["authorized"]);expect(kept.widgets[1].query.unit).toBe("W");
    expect(kept.widgets[kept.widgets.length-1]?.payload.templateVersion).toBe(1);expect(kept.widgets[1].title).toContain("DEMO");
    const removed=instantiateTemplate(refrigeration,"Cold room",{...binding,keepMissing:false});expect(removed.widgets.some((widget)=>widget.query.metric==="temperatureC")).toBe(false);
  });
  it("never mutates a copied version, retains each device gap and rejects unsupported versions/capabilities",()=>{
    const template={...refrigeration,id:"my-devices",panels:[{...refrigeration.panels[0],metric:"activePowerW",unit:"W"}]};
    const snapshot=instantiateTemplate(template,"Mine",{deviceIds:["one","two","three"],locationId:"",missingKeys:["temperature:two"],keepMissing:true,demo:false});
    expect(snapshot.widgets.slice(0,3).map((widget)=>widget.binding.mode)).toEqual(["FIXED","UNBOUND","FIXED"]);
    template.panels[0].title="Updated catalog";expect(snapshot.widgets[0].title).toBe("Temperature · Equipment 1");
    expect(()=>instantiateTemplate({...template,version:2},"Mine",{deviceIds:["one"],locationId:"",missingKeys:[],keepMissing:true,demo:false})).toThrow("unavailable");
    expect(()=>instantiateTemplate({...template,available:false},"Mine",{deviceIds:["one"],locationId:"",missingKeys:[],keepMissing:true,demo:false})).toThrow("unavailable");
  });
});
