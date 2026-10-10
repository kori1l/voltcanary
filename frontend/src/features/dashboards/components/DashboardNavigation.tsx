import "../dashboard.css";
import {useEffect,useState,useSyncExternalStore} from "react";
import {dashboardCatalogRevision,subscribeDashboardCatalog} from "../../../dashboards/dashboardCatalogRevision";
import {Link,useLocation} from "react-router-dom";
import {listDashboards,type DashboardMetadata} from "../../../dashboards/dashboardsApi";
import {useDashboardMenu} from "../model/dashboardMenuPreferences";
export function DashboardNavigation({actorId}:{actorId?:string}){
 const revision=useSyncExternalStore(subscribeDashboardCatalog,dashboardCatalogRevision),menu=useDashboardMenu(actorId);
 const location=useLocation();const [items,setItems]=useState<DashboardMetadata[]>([]),[failed,setFailed]=useState(false);
 useEffect(()=>{const controller=new AbortController();async function load(){try{const all:DashboardMetadata[]=[];let cursor:string|undefined;do{const page=await listDashboards("ACTIVE",cursor,controller.signal);all.push(...page.items);cursor=page.hasNext?page.nextCursor ?? undefined:undefined;}while(cursor && !controller.signal.aborted);if(!controller.signal.aborted){setItems(all);setFailed(false);}}catch{if(!controller.signal.aborted)setFailed(true);}}void load();return()=>controller.abort();},[location.pathname,revision]);
 const visible=items.filter((item)=>item.status==="ACTIVE" && !menu.hidden.includes(item.id)).sort((a,b)=>a.name.localeCompare(b.name));
 return menu.collapsed?null:<nav id="dashboard-menu-list" className="dashboard-sidebar-items" aria-label="Available dashboards">{visible.map((item)=>{const active=location.pathname===`/dashboards/${item.id}` || location.pathname===`/dashboards/${item.id}/edit`;return <Link key={item.id} to={`/dashboards/${item.id}${item.publishedRevision?"":"/edit"}`} className={active?"dashboard-sidebar-link active":"dashboard-sidebar-link"} aria-current={active?"page":undefined} title={item.name}>{item.name}</Link>;})}{failed?<Link className="dashboard-sidebar-link" to="/dashboards">Open dashboard list</Link>:null}</nav>;
}
