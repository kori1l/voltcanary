import {apiRequest} from "../../../api/client";
import {commandKey,type DashboardDefinition,type DashboardWidget} from "../../../dashboards/dashboardsApi";
import {blankDashboard,normalizeLayouts} from "./dashboardLayout";
export interface TemplatePanel {key:string;type:string;title:string;slot:string|null;metric:string|null;unit:string|null;query:Record<string,unknown>;drillDown:string}
export interface DashboardTemplate {id:string;version:number;title:string;task:string;persona:string;segments:string[];available:boolean;explanation:string;period:string;seriesHours:number;slots:string[];panels:TemplatePanel[]}
export const listDashboardTemplates=(signal?:AbortSignal)=>apiRequest<DashboardTemplate[]>("/dashboard-templates",{signal});
export interface TemplateBinding {deviceIds:string[];deviceNames?:Record<string,string>;locationId:string;missingKeys:string[];keepMissing:boolean;demo:boolean;independentMetersConfirmed?:boolean}
/** Materialize a safe independent snapshot. Imported resource IDs must be rebound. */
export function instantiateTemplate(template:DashboardTemplate,name:string,binding:TemplateBinding):DashboardDefinition {
  if(template.version!==1 || !template.available)throw new Error("This template version or capability is unavailable. Choose a supported template.");
  if(!binding.deviceIds.length && !binding.locationId)throw new Error("Choose authorized equipment or a location.");
  if(template.id==="energy" && !binding.independentMetersConfirmed)throw new Error("Review the independent physical meter boundary before creating an energy dashboard.");
  const base=blankDashboard(name);const scope=binding.deviceIds.length?{mode:"FIXED" as const,deviceIds:binding.deviceIds,...(template.id==="refrigeration"?{deviceTypes:["FRIDGE"]}:{})}:{mode:"DYNAMIC" as const,locationIds:[binding.locationId],includeSubtree:false,...(template.id==="refrigeration"?{deviceTypes:["FRIDGE"]}:{})};
  const time={mode:"RELATIVE" as const,preset:template.period as "LAST_24_HOURS"|"LAST_7_DAYS",timezone:"UTC"};
  const widgets:DashboardWidget[]=[];
  for(const panel of template.panels){
    const ids=panel.metric && template.id==="my-devices" && panel.type==="latest-value"?binding.deviceIds:[binding.deviceIds[0]];
    for(const deviceId of ids.length?ids:[undefined]){
      const missing=binding.missingKeys.includes(panel.key) || binding.missingKeys.includes(`${panel.key}:${deviceId}`);if(missing && !binding.keepMissing)continue;
      const query={...panel.query,...(["energy-period","peak-and-ranking"].includes(panel.type)?{independentMetersConfirmed:binding.independentMetersConfirmed===true}:{}),...(panel.metric?{metric:panel.metric,unit:panel.unit}:{}),...(panel.type==="time-series"?{series:deviceId?[{deviceId,metric:panel.metric}]:[],time:{mode:"RELATIVE",preset:template.seriesHours===6?"LAST_6_HOURS":"LAST_24_HOURS",timezone:"UTC"}}:{})};
      const equipment=template.id==="my-devices" && panel.type==="latest-value"?` · ${binding.deviceNames?.[deviceId!] ?? `Equipment ${binding.deviceIds.indexOf(deviceId!)+1}`}`:"";
      widgets.push({id:commandKey(),type:panel.type,typeVersion:1,title:`${binding.demo?"DEMO · ":""}${panel.title}${equipment}${missing?" · Measurement unavailable":""}`.slice(0,120),binding:missing || (panel.metric && !deviceId)?{mode:"UNBOUND"}:panel.slot==="primary"?{mode:"FIXED",deviceIds:[deviceId!]}:scope,query,presentation:{format:"auto",...(panel.type==="risk-list"?{riskFilter:"HIGH_OR_UNKNOWN"}:{})},navigation:{href:panel.drillDown},payload:panel.type==="context-note"?{text:"Review recorded evidence and plan maintenance. Assignment and confirmed cycle data are unavailable."}:{}});
    }
  }
  widgets.push({id:commandKey(),type:"context-note",typeVersion:1,title:binding.demo?"DEMO · Template context":"Template context",binding:{mode:"ALL"},query:{},presentation:{},navigation:{},payload:{text:`Created from ${template.title} version ${template.version}. ${binding.demo?"Demo equipment; values are recorded demo evidence. ":""}Persona ${template.persona} does not grant permissions. Missing temperature, tariffs, cycles or assignments are not inferred.`,templateId:template.id,templateVersion:template.version}});
  return normalizeLayouts({...base,description:template.task,defaultContext:{scope,time},widgets});
}

