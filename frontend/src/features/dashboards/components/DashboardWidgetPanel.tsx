import {Info} from "lucide-react";
import {memo,useEffect,useState} from "react";
import {Dialog} from "../../../components/Dialog";
import { Link } from "react-router-dom";
import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
import type { WidgetQueryPanel } from "../../../dashboards/dashboardQueriesApi";
import { useEvidenceClock } from "../../../components/useEvidenceClock";
import {getDashboardPanelInformation} from "../../../dashboards/dashboardQueriesApi";
import {DashboardEvidenceDetails as Evidence} from "./DashboardEvidenceDetails";
import {DashboardBusinessEvidence} from "./DashboardBusinessEvidence";
const StableEvidence=memo(DashboardBusinessEvidence);

export function DashboardWidgetPanel({widget,panel,stale,onRetry}:{widget:DashboardWidget;panel:WidgetQueryPanel|undefined;stale:boolean;onRetry:()=>void}) {
  const [infoOpen,setInfoOpen]=useState(false);
  const safeLink=widget.navigation?.href && /^\/(devices|alerts|maintenance|settings)(\/[A-Za-z0-9_-]+)*$/.test(widget.navigation.href)?widget.navigation.href:null;
  const ready=panel?.status==="ready";
  const emptyMaintenance=widget.type==="maintenance-agenda" && panel?.status==="no_data" && panel.evaluatedCount>0;
  const data=ready?panel.data:null;
  const {sources,devices,locations,freshness,...primary}=data ?? {};
  const status=panel?({ready:"Recorded evidence",no_data:"No data",no_access:"Access unavailable",unavailable:"Unavailable",unsupported:"Unsupported",invalid:"Configure panel"} as Record<string,string>)[panel.status] ?? panel.status:"Loading";
  function information(){return <div className="dashboard-panel-information"><p>{status}{widget.binding?.mode==="FIXED"?" · Fixed binding":""}</p>{panel?.explanation?<p>{panel.explanation}</p>:null}{widget.binding?.mode==="UNBOUND"?<p>Equipment and measurement have not been selected.</p>:null}{panel?.status==="no_data" && ["energy-period","peak-and-ranking"].includes(widget.type)?<p>No covered energy is available. Check the physical meter, recorded source, method and interval boundaries; unknown consumption is not zero.</p>:null}{ready?<><details><summary>Measurement fields and technical details</summary><Evidence value={primary}/></details>{freshness?<details><summary>Measurement age details</summary><Evidence value={freshness}/></details>:null}{sources?<details><summary>Owner source evidence</summary><Evidence value={sources}/></details>:null}<details><summary>Scope and provenance</summary><Evidence value={{devices,locations,...panel.provenance}}/></details></>:null}{panel?<section className="dashboard-info-summary"><h3>Read summary</h3><Evidence value={{...(panel.time?{from:panel.time.from,to:panel.time.to,timezone:panel.time.timezone}:{}),evaluatedEquipment:`${panel.evaluatedCount} of ${panel.requestedCount}`,receivedAt:panel.receivedAt,measuredAt:panel.measuredAt ?? null,age:`${Math.max(0,Math.floor((Date.now()-Date.parse(panel.receivedAt))/1000))} s`}}/></section>:null}{safeLink?<Link to={safeLink}>Open related view</Link>:null}</div>;}
  const quality=widget.type==="latest-value" && data?.value && typeof data.value==="object"?(data.value as Record<string,unknown>).quality:null;
  return <div className="dashboard-widget-content" aria-busy={!panel}>
    <button type="button" className="ghost-button dashboard-panel-info-button" title="Panel information" aria-label={`Information for ${widget.title}`} onClick={()=>setInfoOpen(true)}><Info size={15}/></button>
    {stale?<span className="dashboard-state-badge" role="status">Stale</span>:null}
    {!ready && !emptyMaintenance?<span className="dashboard-state-badge" role="status">{status}</span>:null}
    {emptyMaintenance?<div><p role="status">No maintenance is scheduled for this period.</p><p className="muted-text">No operations overlap the selected period for available equipment.</p>{panel.time?<p className="muted-text">{new Date(panel.time.from).toLocaleString()} – {new Date(panel.time.to).toLocaleString()}</p>:null}<Link to="/maintenance">Open maintenance calendar</Link></div>:null}
    {quality && quality!=="OK"?<span className="dashboard-state-badge">Quality: {String(quality)}</span>:null}
    {ready?<StableEvidence widget={widget} panel={panel}/>:null}
    {widget.presentation?.showDetails===true?<Information panel={panel}>{information}</Information>:null}
    {panel && !["ready","no_data","unsupported"].includes(panel.status)?<button className="ghost-button" type="button" onClick={onRetry}>Retry {widget.title}</button>:null}
    {infoOpen?<Dialog title={`Information for ${widget.title}`} side onClose={()=>setInfoOpen(false)}><Information panel={panel}>{information}</Information></Dialog>:null}
  </div>;
}

function Information({panel,children}:{panel:WidgetQueryPanel|undefined;children:()=>React.ReactNode}) {
  useEvidenceClock();
  const path=panel?.provenance.detailsPath;
  const [details,setDetails]=useState<{path:string;panel:WidgetQueryPanel}|null>(null),[error,setError]=useState<string|null>(null);
  useEffect(()=>{
    setDetails(null);setError(null);
    if(typeof path!=="string")return;
    const controller=new AbortController();
    void getDashboardPanelInformation(path,controller.signal).then(value=>{if(!controller.signal.aborted)setDetails({path,panel:value});})
      .catch(error=>{if(!controller.signal.aborted)setError(error instanceof Error?error.message:"Source information unavailable");});
    return ()=>controller.abort();
  },[path]);
  return <>{children()}{error?<p role="alert">{error}</p>:null}{details?.path===path?<details><summary>Full owner source snapshot</summary><Evidence value={details?.panel.data?.sources}/></details>:null}</>;
}
