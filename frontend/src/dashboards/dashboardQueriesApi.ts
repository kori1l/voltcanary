import { apiRequest,apiResponseRequest,ApiError,sessionIdentityRevision } from "../api/client";
import type {DashboardDefinition} from "./dashboardsApi";

/** Unsaved preview uses the ordinary owner-read pipeline without creating a dashboard. */
export function previewDashboardDefinition(definition:DashboardDefinition, signal?:AbortSignal) {
  return apiRequest<DashboardQueryBatch>("/dashboards/preview/query", {
    method:"POST", body:JSON.stringify({definition}), signal,
  });
}

export type WidgetType = "fleet-count" | "latest-value" | "time-series" | "metric-summary" | "incident-list"
  | "maintenance-agenda" | "risk-list" | "quality-and-freshness" | "location-matrix" | "prediction-evidence" | "context-note" | "energy-period" | "peak-and-ranking" | "estimated-cost" | "uptime-or-cycle";
export interface DashboardScope {
  workspaceId?:string;
  accountId?:string;
  mode?: "ALL" | "DYNAMIC" | "FIXED" | "UNBOUND";
  deviceIds?: string[];
  locationIds?: string[];
  includeSubtree?: boolean;
  deviceTypes?: string[];
  locationTags?: string[];
}
export interface DashboardTime {
  mode?: "INHERITED" | "RELATIVE" | "CALENDAR" | "FIXED";
  preset?: "LAST_1_HOUR" | "LAST_6_HOURS" | "LAST_24_HOURS" | "LAST_7_DAYS" | "LAST_30_DAYS" | "TODAY" | "WEEK_TO_DATE" | "MONTH_TO_DATE" | "NEXT_7_DAYS" | "NEXT_30_DAYS";
  timezone?: string;
  from?: string;
  to?: string;
}
export interface DashboardQueryRequest {
  revision?: number;
  draftEtag?: string;
  runtimeContext?: { scope?: DashboardScope; time?: DashboardTime };
  forceRefresh?: boolean;
  widgetIds?: string[];
}
export interface QueryFrame {
  from: string;
  to: string;
  asOf: string;
  timezone: string;
  override: boolean;
  compareFrom: string | null;
  compareTo: string | null;
  comparisonBasis: string;
}
export interface WidgetQueryPanel {
  widgetId: string;
  status: "ready" | "no_data" | "unavailable" | "no_access" | "unsupported" | "invalid" | "valid";
  data: Record<string, unknown> | null;
  contextHash: string;
  time: QueryFrame | null;
  requestedCount: number;
  evaluatedCount: number;
  receivedAt: string;
  measuredAt: string | null;
  provenance: Record<string, unknown>;
  errorCode: string | null;
  explanation: string | null;
}
export interface DashboardQueryBatch {
  dashboardId: string;
  revision: number;
  draftEtag: string | null;
  contextHash: string;
  asOf: string;
  returnedAt: string;
  cached: boolean;
  panels: WidgetQueryPanel[];
  scopes?: Record<string, {devices: unknown[]; locations: unknown[]}>;
}
export interface WidgetPresentationSetting {
  key:string;
  type:"BOOLEAN"|"AUTO_BOOLEAN"|"TEXT"|"SELECT"|"FIELD_SET";
  section:"GENERAL"|"APPEARANCE";
  group:string;
  label:string;
  defaultValue:boolean|string|null;
  choices:{value:string;label:string}[];
  maxLength:number|null;
}
export interface WidgetCapabilities {
  version:number;
  dataKind:string;
  valueTypes:string[];
  binding:{modes:string[];maxDevices:number;sourceRequired:boolean;metricRequired:boolean};
  visualizations:string[];
  visualizationValueTypes?:Record<string,string[]>;
  settings:WidgetPresentationSetting[];
}
export interface WidgetManifest {
  capabilities?:WidgetCapabilities;
  visualizations?:string[];
  type: WidgetType | string;
  typeVersion: number;
  title: string;
  resultType: string;
  supported: boolean;
  dependency: string | null;
  reducers: string[];
  units: string[];
  maxSeries: number;
  maxDetailDevices: number;
  maxRows: number;
  timeBasis: string;
  sizeConstraints?: { desktopMinColumns: number; tabletMinColumns: number; mobileMinColumns: number; minRows: number };
}
export async function queryDashboard(id: string, request: DashboardQueryRequest, signal?: AbortSignal, onProgress?: (batch: DashboardQueryBatch)=>void): Promise<DashboardQueryBatch> {
  if(onProgress)return progressiveDashboard(id,request,signal,onProgress);
  const batch = await apiRequest<DashboardQueryBatch>(`/dashboards/${encodeURIComponent(id)}/query`, {
    method: "POST", body: JSON.stringify(request), signal,
  });
  return expandDashboardBatch(id,batch);
}
function expandDashboardBatch(id: string,batch: DashboardQueryBatch): DashboardQueryBatch {
  return {...batch,panels:batch.panels.map(panel=>{
    const scope=typeof panel.data?.scopeRef==="string"?batch.scopes?.[panel.data.scopeRef]:undefined;
    return {...panel,data:panel.data && scope?{...panel.data,...scope}:panel.data,
      provenance:{...panel.provenance,detailsPath:`/dashboards/${encodeURIComponent(id)}/query-snapshots/${encodeURIComponent(panel.contextHash)}/panels/${encodeURIComponent(panel.widgetId)}`}};
  })};
}
async function progressiveDashboard(id: string,request:DashboardQueryRequest,signal:AbortSignal|undefined,onProgress:(batch:DashboardQueryBatch)=>void):Promise<DashboardQueryBatch> {
  const identity=sessionIdentityRevision();
  const response=await apiResponseRequest(`/dashboards/${encodeURIComponent(id)}/query-progressive`,{method:"POST",body:JSON.stringify(request),signal});
  if(!response.body)throw new ApiError("Progressive response unavailable",503);
  const reader=response.body.getReader(),decoder=new TextDecoder();
  let buffered="",result:DashboardQueryBatch|null=null,complete=false;
  const scopes:NonNullable<DashboardQueryBatch["scopes"]>={};
  const consume=(line:string)=>{
    if(signal?.aborted)throw new DOMException("Obsolete read","AbortError");
    if(identity!==sessionIdentityRevision())throw new ApiError("Authentication scope changed",401);
    const value=JSON.parse(line) as DashboardQueryBatch & {complete?:boolean;errorCode?:string;explanation?:string};
    if(value.errorCode)throw new ApiError(value.explanation ?? "Dashboard read unavailable",value.errorCode==="NO_ACCESS"?403:503,value.errorCode);
    if(value.complete){if(value.contextHash!==result?.contextHash)throw new ApiError("Invalid dashboard completion",503);complete=true;return;}
    if(result && (result.contextHash!==value.contextHash || result.asOf!==value.asOf))throw new ApiError("Dashboard snapshot changed during delivery",503);
    Object.assign(scopes,value.scopes);
    const group=expandDashboardBatch(id,{...value,scopes});
    const panels=new Map(result?.panels.map(panel=>[panel.widgetId,panel]) ?? []);
    group.panels.forEach(panel=>panels.set(panel.widgetId,panel));
    result={...group,panels:[...panels.values()]};
    onProgress(result);
  };
  try {
    while(true){const chunk=await reader.read();if(chunk.done)break;buffered+=decoder.decode(chunk.value,{stream:true});
      if(buffered.length>20_000_000)throw new ApiError("Dashboard response exceeds delivery budget",503);
      let newline:number;while((newline=buffered.indexOf("\n"))>=0){const line=buffered.slice(0,newline);buffered=buffered.slice(newline+1);if(line.trim())consume(line);}
    }
    buffered+=decoder.decode();if(buffered.trim())consume(buffered);
    if(!complete || !result)throw new ApiError("Dashboard delivery ended before completion",503);
    return result;
  } finally {await reader.cancel().catch(()=>{});reader.releaseLock();}
}
export function getDashboardPanelInformation(path: string, signal?: AbortSignal) {
  return apiRequest<WidgetQueryPanel>(path,{signal});
}
export function validateDashboardQuery(id: string, request: DashboardQueryRequest, signal?: AbortSignal) {
  return apiRequest<DashboardQueryBatch>(`/dashboards/${encodeURIComponent(id)}/query-validation`, {
    method: "POST", body: JSON.stringify(request), signal,
  });
}
export function listWidgetTypes(signal?: AbortSignal) {
  return apiRequest<WidgetManifest[]>("/dashboard-widget-types", { signal });
}
