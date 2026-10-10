import {useState} from "react";
import {Link} from "react-router-dom";
import {Dialog} from "../../../components/Dialog";
import {ReadRefreshControl} from "../../../components/ReadRefresh";
import type {DashboardView,RuntimeContext} from "../../../dashboards/dashboardsApi";
import {DashboardManagement} from "./DashboardManagement";
import {DashboardRuntimeControls} from "./DashboardRuntimeControls";
type Props={view:DashboardView;context:RuntimeContext;kioskUrl:string;refreshInterval:number;onInterval:(value:number)=>void;refreshing:boolean;busy:boolean;onRefresh:()=>void;onSaveDefaults:()=>void;onLifecycle:()=>void;onClose:()=>void};
export function DashboardViewerSettings({view,context,kioskUrl,refreshInterval,onInterval,refreshing,busy,onRefresh,onSaveDefaults,onLifecycle,onClose}:Props){
 const [section,setSection]=useState("Data and time");const active=!!view.definition && view.dashboard.status==="ACTIVE";
 return <Dialog title="Dashboard settings" side onClose={onClose}><div className="dashboard-view-settings"><nav className="dashboard-config-sections" aria-label="Dashboard settings sections">{["Data and time","Refresh","Manage"].map((name)=><button className="ghost-button" aria-pressed={section===name} onClick={()=>setSection(name)} key={name}>{name}</button>)}</nav>
 {section==="Data and time"?<section className="dashboard-settings-data"><h3>Data and time</h3>{active?<><DashboardRuntimeControls key={JSON.stringify(context)} context={context}/><button className="ghost-button" disabled={busy} onClick={onSaveDefaults}>Save personal runtime defaults</button></>:<p>Runtime filters are available for an active published dashboard.</p>}</section>:null}
 {section==="Refresh"?<section className="dashboard-settings-section"><h3>Refresh</h3>{active?<><ReadRefreshControl interval={refreshInterval} onChange={onInterval}/><button className="ghost-button" disabled={refreshing} onClick={onRefresh}>Refresh evidence</button><p className="muted-text">Automatic refresh continues after settings close and pauses while the browser tab is hidden.</p></>:<p>Refresh is available for an active published dashboard.</p>}</section>:null}
 {section==="Manage"?<><section className="dashboard-settings-section"><h3>Navigation</h3><div className="action-row"><Link to="/dashboards">All dashboards</Link>{active?<Link to={kioskUrl}>Open kiosk</Link>:null}</div></section><DashboardManagement id={view.dashboard.id} name={view.dashboard.name} published={view.dashboard.publishedRevision>0 && view.dashboard.status==="ACTIVE"} disabled={busy}/>{view.permissions.some((permission)=>["archive","restore"].includes(permission))?<section className="dashboard-settings-section"><h3>{view.dashboard.status==="ACTIVE"?"Archive":"Restore"}</h3><p className="muted-text">{view.dashboard.status==="ACTIVE"?"Preserves the dashboard and revisions. You can restore it later.":"Restores access to this dashboard."}</p><button className="ghost-button" disabled={busy} onClick={onLifecycle}>{view.dashboard.status==="ACTIVE"?"Archive dashboard":"Restore dashboard"}</button></section>:null}</>:null}
 </div></Dialog>;
}
