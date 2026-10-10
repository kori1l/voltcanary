import { useCallback, useEffect, useRef, useState } from "react";
import { Link } from "react-router-dom";
import {SidebarToggle} from "../../../components/NavigationVisibility";
import { Dialog } from "../../../components/Dialog";
import {Settings2} from "lucide-react";
import {useReadRefresh} from "../../../components/useReadRefresh";
import { useConsoleQuery } from "../../../app/useConsoleQuery";
import { useDashboardQuery } from "../../../dashboards/useDashboardQuery";
import { getDashboardPreferences, saveDashboardPreferences, transitionDashboard, type DashboardView } from "../../../dashboards/dashboardsApi";
import {DashboardViewerSettings} from "./DashboardViewerSettings";
import { DashboardCanvas } from "./DashboardCanvas";
import { runtimeFromUrl } from "./DashboardRuntimeControls";
import {dashboardScreens,screenDefinition} from "../model/dashboardScreens";
import {DashboardScreenNavigation} from "./DashboardScreenNavigation";
export function DashboardViewer({view,reload}:{view:DashboardView;reload:()=>Promise<unknown>}) {
  const query=useConsoleQuery();const kiosk=query.params.get("kiosk")==="true";const definition=view.definition;
  const [screenToolbar,setScreenToolbar]=useState<HTMLDivElement|null>(null);
  const [filtersOpen,setFiltersOpen]=useState(false);
  const [refreshInterval,setRefreshInterval]=useState(0);
  const [error,setError]=useState<string|null>(null);const [message,setMessage]=useState("");const [confirm,setConfirm]=useState(false);const [busy,setBusy]=useState(false);const lock=useRef(false);
  const [personalContext,setPersonalContext]=useState<import("../../../dashboards/dashboardsApi").RuntimeContext|null>(null);
  useEffect(()=>{let active=true;void getDashboardPreferences().then((preferences)=>{if(active && Object.keys(preferences.defaultContext ?? {}).length)setPersonalContext(preferences.defaultContext);}).catch(()=>{});return()=>{active=false;};},[view.dashboard.id]);
  const [visible,setVisible]=useState<string[]>([]);
  const onVisible=useCallback((ids:string[])=>setVisible((prior)=>prior.join(",")===ids.join(",")?prior:ids),[]);
  const context=runtimeFromUrl(query.params,personalContext ?? definition?.defaultContext ?? {});
  const screens=definition?dashboardScreens(definition):[],requestedScreen=query.params.get("screenId"),active=screens.find((screen)=>screen.id===requestedScreen) ?? screens[0];
  const canvas=definition && active?screenDefinition(definition,active.id):null;
  const activeVisible=visible.filter((id)=>active?.widgetIds.includes(id));
  const evidence=useDashboardQuery(view.dashboard.id,{revision:view.revision,runtimeContext:context,widgetIds:activeVisible},!!canvas && view.dashboard.status==="ACTIVE" && activeVisible.length>0,active?.id);
  useReadRefresh({scope:`dashboard:${view.dashboard.id}:${JSON.stringify(context)}`,refresh:evidence.refresh,interval:refreshInterval});
  const kioskParams=new URLSearchParams(query.params);kioskParams.set("kiosk","true");
  async function saveRuntimeDefaults(){if(lock.current)return;lock.current=true;setBusy(true);setError(null);try{const preferences=await getDashboardPreferences();await saveDashboardPreferences({...preferences,defaultContext:context});setMessage("Personal runtime defaults saved");}catch(error){setError(error instanceof Error?error.message:"Preference failed");}finally{lock.current=false;setBusy(false);}}
  async function lifecycle(){if(lock.current)return;lock.current=true;setBusy(true);setError(null);try{await transitionDashboard(view.dashboard.id,view.draftEtag,view.dashboard.status==="ACTIVE");setConfirm(false);await reload();setFiltersOpen(false);}catch(error){setError(error instanceof Error?error.message:"Lifecycle command failed");}finally{lock.current=false;setBusy(false);}}
  return <div className="dashboard-viewer"><header className="dashboard-workspace-header"><div className="dashboard-workspace-title">{!kiosk?<SidebarToggle/>:null}<h1>{view.dashboard.name}</h1><div ref={setScreenToolbar} className="dashboard-screen-slot"/><span className="dashboard-save-state">v{view.revision}</span></div>
    {kiosk?<p>Authenticated read-only kiosk</p>:<div className="dashboard-header-links">{view.permissions.includes("edit")?<Link to={`/dashboards/${view.dashboard.id}/edit${active?`?screenId=${active.id}`:""}`}>Edit draft</Link>:null}<button className="ghost-button" aria-label="Dashboard settings" title="Dashboard settings" onClick={()=>setFiltersOpen(true)}><Settings2 size={18}/></button></div>}</header>
    {message?<p role="status">{message}</p>:null}{error?<p role="alert">{error}<button className="ghost-button" onClick={()=>void reload().then(()=>{setError(null);setConfirm(false);})}>Reload dashboard before retry</button></p>:null}
    {!definition?<p>This dashboard is a private draft. Configure and preview it in Edit before publication.</p>:view.dashboard.status==="ARCHIVED"?<p>Archived dashboard. Restore it to resume authorized evidence reads.</p>:<>
      {evidence.error?<p role="alert">Connectivity or evidence read failed: {evidence.error}. {evidence.status==="stale"?"Retained evidence is stale.":"No evidence available."}</p>:null}
      <p className="sr-only" role="status">Snapshot {evidence.data?.asOf ?? "unavailable"}{evidence.data?.cached?" · Cached snapshot":""}.</p>
      {requestedScreen && active?.id!==requestedScreen?<p role="status">Requested screen is unavailable. Showing {active?.title}.</p>:null}
      {active && canvas?<DashboardScreenNavigation definition={definition} activeId={active.id} onSelect={(screenId)=>query.update({screenId})} toolbarTarget={screenToolbar}><h2 className="sr-only">{active.title}</h2><DashboardCanvas definition={canvas} panels={evidence.data?.panels ?? []} stale={evidence.status==="stale"} onRetry={()=>void evidence.refresh()} onVisible={onVisible}/></DashboardScreenNavigation>:null}
    </>}
    {!kiosk && filtersOpen?<DashboardViewerSettings view={view} context={context} kioskUrl={`/dashboards/${view.dashboard.id}?${kioskParams}`} refreshInterval={refreshInterval} onInterval={setRefreshInterval} refreshing={evidence.refreshing} busy={busy} onRefresh={()=>void evidence.refresh()} onSaveDefaults={()=>void saveRuntimeDefaults()} onLifecycle={()=>setConfirm(true)} onClose={()=>setFiltersOpen(false)}/>:null}
    {confirm?<Dialog title={view.dashboard.status==="ACTIVE"?"Archive dashboard":"Restore dashboard"} busy={busy} onClose={()=>setConfirm(false)}><p>{view.dashboard.status==="ACTIVE"?"Archive preserves this dashboard and its revisions, clears favorites and personal default. The default entry falls back to the system overview.":"Restore retains this ID. Set the personal default again explicitly if needed."}</p><button className="primary-button" disabled={busy} onClick={()=>void lifecycle()}>Confirm {view.dashboard.status==="ACTIVE"?"archive":"restore"}</button></Dialog>:null}
  </div>;
}
