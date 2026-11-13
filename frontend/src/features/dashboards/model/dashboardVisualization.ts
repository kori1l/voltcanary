import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import type {WidgetQueryPanel} from "../../../dashboards/dashboardQueriesApi";

export const visualizations = ["list", "number", "line", "area", "bar", "horizontal-bar", "stacked-bar", "pie", "donut"] as const;
export type Visualization = typeof visualizations[number];
export const visualizationLabels:Record<Visualization,string> = {list:"Recorded observations",number:"Number",line:"Line",area:"Area",bar:"Vertical bars","horizontal-bar":"Horizontal bars","stacked-bar":"Stacked bars",pie:"Pie",donut:"Donut"};
export function compatibleVisualizations(type:string):Visualization[] {
  if (["fleet-count","quality-and-freshness"].includes(type)) return ["number","bar","horizontal-bar","stacked-bar","pie","donut"];
  if (type === "vehicle-timeline") return ["list","line"];
  if (type === "time-series") return ["line","area","bar","horizontal-bar"];
  if (type==="energy-period") return ["number","bar","horizontal-bar","pie","donut"];
  if (["latest-value","metric-summary","estimated-cost","uptime-or-cycle"].includes(type)) return ["number"];
  return [];
}
export interface ChartValue {label:string;value:number;unit:string;at?:string;from?:string;gap?:boolean}
export interface DiagramEvidence {values:ChartValue[];reason?:string;total?:number;partial:boolean}
const row=(v:unknown):Record<string,unknown>=>v && typeof v==="object"?v as Record<string,unknown>:{};
// Fresh/stale/unknown/manual/sparse are disjoint states. Registered/active overlap them.
export function diagramEvidence(widget:DashboardWidget,panel:WidgetQueryPanel):DiagramEvidence {
  const data=row(panel.data), values:ChartValue[]=[];
  const partial=panel.requestedCount!==panel.evaluatedCount || row(panel.provenance).partial===true;
  if (panel.status!=="ready") return {values,partial,reason:"Current authorized evidence is unavailable"};
  if(widget.type==="energy-period") {
    const energy=row(data.energy), devices=Array.isArray(energy.devices)?energy.devices:[];
    if(energy.coverage!=="COMPLETE")return {values,partial,reason:"Distribution requires complete covered energy"};
    for(const entry of devices){const device=row(entry),value=device.energyKWh;if(typeof value!=="number" || !Number.isFinite(value) || value<0)return {values:[],partial,reason:"Equipment energy is unknown or incompatible"};values.push({label:String(device.deviceName ?? "Equipment"),value,unit:"kWh"});}
    const total=values.reduce((sum,v)=>sum+v.value,0);
    if(typeof energy.energyKWh!=="number" || Math.abs(total-energy.energyKWh)>Math.max(1e-8,total*1e-8))return {values:[],partial,reason:"Equipment energy does not match the covered total"};
    return {values,total,partial,reason:total===0?"Zero total — no covered energy":undefined};
  }
  if (["fleet-count","quality-and-freshness"].includes(widget.type)) {
    const counts=row(data.counts);
    for (const [key,label] of [["fresh","Fresh"],["stale","Stale"],["unknown","Unknown age"],["manualImport","Manual import"],["sparse","Sparse"]]) {
      const value=counts[key];
      if (typeof value!=="number" || !Number.isFinite(value) || value<0) return {values:[],partial,reason:"Categories require known nonnegative counts"};
      values.push({label,value,unit:"equipment"});
    }
    const total=values.reduce((sum,item)=>sum+item.value,0);
    if (!Number.isFinite(total) || total!==counts.registered) return {values:[],partial,reason:"Category totals do not match the authorized scope"};
    return {values,total,partial,reason:total===0?"Zero total — no equipment":undefined};
  }
  if (widget.type==="time-series") {
    const series=Array.isArray(data.series)?data.series:[];
    if (series.length!==1) return {values,partial,reason:"Use the default chart for multiple series"};
    const reducer=String(data.reducer ?? "RAW").toUpperCase();
    for (const bucket of (Array.isArray(row(series[0]).buckets)?row(series[0]).buckets:[]) as unknown[]) {
      const bin=row(bucket),value=bin[reducer==="MIN"?"minimum":reducer==="MAX"?"maximum":reducer==="AVG"?"mean":"last_value"];
      if (typeof value!=="number" || !Number.isFinite(value) || typeof bin.lastAt!=="string" || !Number.isFinite(Date.parse(bin.lastAt))) return {values:[],partial,reason:"Recorded bins are incomplete"};
      values.push({label:bin.lastAt,value,unit:String(bin.unit ?? ""),at:bin.lastAt,from:typeof bin.firstAt==="string"?bin.firstAt:undefined,gap:Number(bin.gapCount)>0});
    }
    if (new Set(values.map(item=>item.unit)).size>1) return {values:[],partial,reason:"Recorded units are incompatible"};
    return {values,partial,reason:values.length?undefined:"No recorded samples"};
  }
  return {values,partial,reason:"This evidence has no disjoint categories or recorded series"};
}
