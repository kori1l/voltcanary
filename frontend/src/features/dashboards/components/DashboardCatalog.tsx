import { useEffect, useState, useSyncExternalStore } from "react";
import { Link } from "react-router-dom";
import { PageHeader } from "../../../components/PageHeader";
import { useSectionResource } from "../../../components/useSectionResource";
import { getDashboardPreferences, listDashboards, type DashboardMetadata, type DashboardPreferences } from "../../../dashboards/dashboardsApi";
import {Ellipsis} from "lucide-react";
import {me} from "../../../auth/authApi";
import {dashboardCatalogRevision,subscribeDashboardCatalog} from "../../../dashboards/dashboardCatalogRevision";
import {updateDashboardMenu,useDashboardMenu} from "../model/dashboardMenuPreferences";
import {DashboardItemActions} from "./DashboardItemActions";
import {DashboardCreation} from "./DashboardCreation";

export function DashboardCatalog({actorId:providedActor}:{actorId?:string}={}) {
  const [actorId,setActorId]=useState(providedActor);
  useEffect(()=>{if(providedActor){setActorId(providedActor);return;}let mounted=true;void me().then((actor)=>{if(mounted)setActorId(actor.id);}).catch(()=>{});return()=>{mounted=false;};},[providedActor]);
  const menu=useDashboardMenu(actorId),revision=useSyncExternalStore(subscribeDashboardCatalog,dashboardCatalogRevision);
  const [selected,setSelected]=useState<DashboardMetadata|null>(null);
  const resource=useSectionResource<DashboardMetadata[]>("dashboard-catalog");
  const preferences=useSectionResource<DashboardPreferences>("dashboard-preferences");
  const [tab,setTab]=useState("All");const [query,setQuery]=useState("");const [sort,setSort]=useState("name");
  const [creating,setCreating]=useState(false);
  async function reload(){return resource.load(async()=>{let cursor:string|undefined;const all:DashboardMetadata[]=[];do{const page=await listDashboards("ALL",cursor);all.push(...page.items);cursor=page.hasNext?page.nextCursor ?? undefined:undefined;}while(cursor);return all;});}
  useEffect(()=>{void reload();void preferences.load(()=>getDashboardPreferences());},[resource.load,preferences.load,revision]);
  const visible=(resource.data ?? []).filter((item)=>(tab==="Archived"?item.status==="ARCHIVED":item.status==="ACTIVE") && (tab!=="Favorites" || preferences.data?.favorites.includes(item.id)) && (tab!=="Hidden" || menu.hidden.includes(item.id)) && `${item.name} ${item.description ?? ""}`.toLowerCase().includes(query.toLowerCase())).sort((a,b)=>sort==="updated"?b.updatedAt.localeCompare(a.updatedAt):a.name.localeCompare(b.name));
  const actions=<div className="action-row dashboard-catalog-actions"><button className="primary-button" onClick={()=>setCreating(true)}>New dashboard</button><button type="button" className="ghost-button" onClick={()=>void reload()}>Refresh list</button></div>;
  return <><PageHeader title="Dashboards" actions={actions}/>
    <div className="action-row dashboard-catalog-filters">{["All","Favorites","Hidden","Archived"].map((value)=><button type="button" className="ghost-button" aria-pressed={tab===value} key={value} onClick={()=>setTab(value)}>{value}</button>)}<Link to="/dashboard?overview=true">System overview</Link><button className="ghost-button" disabled={!actorId} onClick={()=>{if(actorId)updateDashboardMenu(actorId,(current)=>({...current,collapsed:!current.collapsed}));}}>{menu.collapsed?"Show sidebar dashboards":"Hide sidebar dashboards"}</button></div>
    <div className="dashboard-toolbar"><label>Search dashboards <input type="search" value={query} maxLength={200} onChange={(event)=>setQuery(event.target.value)}/></label><label>Sort dashboards <select value={sort} onChange={(event)=>setSort(event.target.value)}><option value="name">Name</option><option value="updated">Recently updated</option></select></label></div>
    {resource.error || preferences.error?<p role="alert">{resource.error ?? preferences.error}<button type="button" className="ghost-button" onClick={()=>{void reload();void preferences.load(()=>getDashboardPreferences());}}>Retry catalog and preferences</button></p>:null}
    {!resource.data?<p role="status">Loading dashboards…</p>:visible.length?<ul className="dashboard-catalog dashboard-catalog-list">{visible.map((item)=><li key={item.id}><Link to={`/dashboards/${item.id}${!item.publishedRevision && item.status==="ACTIVE"?"/edit":""}`} title={item.description || undefined}>{item.name}</Link><small>{item.status==="ARCHIVED"?"Archived":item.publishedRevision?`Published / v${item.publishedRevision}`:"Draft"}{preferences.data?.defaultDashboardId===item.id?" / Default":""}</small><button className="dashboard-menu-icon" aria-label={`Actions for ${item.name}`} title={`Actions for ${item.name}`} onClick={()=>setSelected(item)}><Ellipsis size={18}/></button></li>)}</ul>:<p>No dashboards match this view. Use New dashboard to create one.</p>}
    {selected?<DashboardItemActions item={selected} actorId={actorId} onClose={()=>setSelected(null)}/>:null}
    {creating?<DashboardCreation onClose={()=>setCreating(false)}/>:null}
  </>;
}
