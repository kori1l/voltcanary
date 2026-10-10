import {SidebarToggle} from "../components/NavigationVisibility";
import { useEffect } from "react";
import { Link, Navigate, useLocation, useParams } from "react-router-dom";
import { getDashboard, getDashboardDraft, getDashboardPreferences, type DashboardDraft, type DashboardPreferences, type DashboardView } from "../dashboards/dashboardsApi";
import { useSectionResource } from "../components/useSectionResource";
import { DashboardCatalog } from "../features/dashboards/components/DashboardCatalog";
import { DashboardEditor } from "../features/dashboards/components/DashboardEditor";
import { DashboardViewer } from "../features/dashboards/components/DashboardViewer";
import { DashboardPage } from "./DashboardPage";
import { sessionIdentityRevision, subscribeSession } from "../api/client";
import { useSyncExternalStore } from "react";
import "../features/dashboards/dashboard.css";
export function PersonalDashboardsPage(){const identity=useSyncExternalStore(subscribeSession,sessionIdentityRevision);return <DashboardCatalog key={identity}/>;}
export function PersonalDashboardPage(){
  const {dashboardId=""}=useParams();const location=useLocation();const identity=useSyncExternalStore(subscribeSession,sessionIdentityRevision);
  const editing=location.pathname.endsWith("/edit");const resource=useSectionResource<{view:DashboardView;draft:DashboardDraft|null;definitionReadyAt:number}>(`dashboard:${dashboardId}:${editing}`);
  const reload=()=>resource.load(async()=>{const view=await getDashboard(dashboardId);const draft=editing && view.permissions.includes("edit")?await getDashboardDraft(dashboardId):null;return {view,draft,definitionReadyAt:performance.now()};});
  useEffect(()=>{void reload();},[resource.load,dashboardId,editing]);
  if(!resource.data)return <section><SidebarToggle/><h1>Dashboard</h1><p role={resource.error?"alert":"status"}>{resource.error ?? "Loading authorized dashboard…"}</p>{resource.error?<button className="ghost-button" onClick={()=>void reload()}>Retry dashboard</button>:null}<Link to="/dashboards">All dashboards</Link></section>;
  const {view,draft}=resource.data;
  if(editing && !draft)return <><SidebarToggle/><p role="alert">Editing is unavailable for this dashboard.</p><Link to={`/dashboards/${dashboardId}`}>Return to view</Link></>;
  return editing && draft?<DashboardEditor key={`${identity}:${dashboardId}`} metadata={view.dashboard} initial={draft} definitionReadyAt={resource.data.definitionReadyAt}/>:<DashboardViewer key={`${identity}:${dashboardId}:${view.dashboard.status}`} view={view} reload={reload}/>;
}
export function DashboardDefaultPage(){
  const location=useLocation();const overview=new URLSearchParams(location.search).get("overview")==="true";
  const resource=useSectionResource<DashboardPreferences>("dashboard-default");
  useEffect(()=>{if(!overview)void resource.load(()=>getDashboardPreferences());},[overview,resource.load]);
  if(overview)return <><Link to="/dashboards">All dashboards</Link><DashboardPage/></>;
  if(resource.data?.defaultDashboardId)return <Navigate replace to={`/dashboards/${resource.data.defaultDashboardId}`}/>;
  if(!resource.data && !resource.error)return <p role="status">Loading personal dashboard preference…</p>;
  return <><p role="status">{resource.error?`Personal default unavailable: ${resource.error}`:resource.data?.fallbackReason ?? "No personal default selected"}. Showing system overview.</p><Link to="/dashboards">Choose or create a dashboard</Link><DashboardPage/></>;
}
