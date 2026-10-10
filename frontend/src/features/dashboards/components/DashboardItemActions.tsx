import {useEffect,useRef,useState} from "react";
import {Link,useNavigate} from "react-router-dom";
import {Dialog} from "../../../components/Dialog";
import {getDashboard,transitionDashboard,type DashboardMetadata,type DashboardView} from "../../../dashboards/dashboardsApi";
import {sessionIdentityRevision} from "../../../api/client";
import {DashboardManagement} from "./DashboardManagement";
import {updateDashboardMenu,useDashboardMenu} from "../model/dashboardMenuPreferences";
export function DashboardItemActions({item,actorId,onClose}:{item:DashboardMetadata;actorId?:string;onClose:()=>void}){
 const navigate=useNavigate(),menu=useDashboardMenu(actorId);
 const [view,setView]=useState<DashboardView|null>(null),[error,setError]=useState<string|null>(null),[busy,setBusy]=useState(false),[confirm,setConfirm]=useState(false);
 const lock=useRef(false);
 useEffect(()=>{const controller=new AbortController();void getDashboard(item.id,controller.signal).then((value)=>{if(!controller.signal.aborted)setView(value);}).catch((e)=>{if(!controller.signal.aborted)setError(e instanceof Error?e.message:"Cannot load permissions");});return()=>controller.abort();},[item.id]);
 const hidden=menu.hidden.includes(item.id),archived=item.status==="ARCHIVED";
 async function lifecycle(){if(lock.current || !view)return;lock.current=true;setBusy(true);setError(null);const identity=sessionIdentityRevision();try{await transitionDashboard(item.id,view.draftEtag,!archived);if(identity===sessionIdentityRevision()){onClose();navigate(archived?`/dashboards/${item.id}`:"/dashboard");}}catch(e){if(identity===sessionIdentityRevision())setError(e instanceof Error?e.message:"Command failed");}finally{lock.current=false;setBusy(false);}}
 return <Dialog title={`Manage ${item.name}`} side busy={busy} onClose={onClose}><div className="dashboard-view-settings">
 <section className="dashboard-settings-section"><h3>Open</h3><div className="action-row"><Link to={`/dashboards/${item.id}`} onClick={onClose}>Open dashboard</Link>{view?.permissions.includes("edit")?<Link to={`/dashboards/${item.id}/edit`} onClick={onClose}>Edit dashboard</Link>:null}</div></section>
 <DashboardManagement id={item.id} name={item.name} published={!!item.publishedRevision && !archived} disabled={busy}/>
 {!archived?<section className="dashboard-settings-section"><h3>Navigation menu</h3><p className="muted-text">This choice affects your menu on this browser.</p><button className="ghost-button" disabled={!actorId || busy} onClick={()=>{if(actorId)updateDashboardMenu(actorId,(current)=>({...current,hidden:hidden?current.hidden.filter((id)=>id!==item.id):[...current.hidden,item.id]}));}}>{hidden?"Show in menu":"Hide from menu"}</button></section>:null}
 {view?.permissions.includes(archived?"restore":"archive")?<section className="dashboard-settings-section"><h3>{archived?"Restore":"Archive"}</h3><p className="muted-text">{archived?"Restore access to the dashboard.":"Preserves the dashboard and revisions; you can restore it later."}</p>{confirm?<div className="action-row"><button className="danger-button" disabled={busy} onClick={()=>void lifecycle()}>Confirm archive</button><button className="ghost-button" disabled={busy} onClick={()=>setConfirm(false)}>Cancel</button></div>:<button className="ghost-button" disabled={busy} onClick={()=>archived?void lifecycle():setConfirm(true)}>{archived?"Restore dashboard":"Archive dashboard"}</button>}</section>:null}
 {error?<p role="alert">{error}</p>:null}</div></Dialog>;
}
