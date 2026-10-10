import {Info} from "lucide-react";
import {useState} from "react";
import {Dialog} from "../../../components/Dialog";
import { Link } from "react-router-dom";
import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
import type { WidgetQueryPanel } from "../../../dashboards/dashboardQueriesApi";
import { useEvidenceClock } from "../../../components/useEvidenceClock";
import {DashboardEvidenceDetails as Evidence} from "./DashboardEvidenceDetails";
import {DashboardBusinessEvidence} from "./DashboardBusinessEvidence";

export function DashboardWidgetPanel({widget,panel,stale,onRetry}:{widget:DashboardWidget;panel:WidgetQueryPanel|undefined;stale:boolean;onRetry:()=>void}) {
  useEvidenceClock();
  const [infoOpen,setInfoOpen]=useState(false);
  const safeLink=widget.navigation?.href && /^\/(devices|alerts|maintenance|settings)(\/[A-Za-z0-9_-]+)*$/.test(widget.navigation.href)?widget.navigation.href:null;
  const ready=panel?.status==="ready";
  const data=ready?panel.data:null;
  const {sources,devices,locations,freshness,...primary}=data ?? {};
  const status=panel?({ready:"Recorded evidence",no_data:"No data",no_access:"Access unavailable",unavailable:"Unavailable",unsupported:"Unsupported",invalid:"Configure panel"} as Record<string,string>)[panel.status] ?? panel.status:"Loading";
  function information(){return <div className="dashboard-panel-information"><p>{status}{widget.binding?.mode==="FIXED"?" · Fixed binding":""}</p>{panel?.explanation?<p>{panel.explanation}</p>:null}{widget.binding?.mode==="UNBOUND"?<p>Equipment and measurement have not been selected.</p>:null}{panel?.status==="no_data" && ["energy-period","peak-and-ranking"].includes(widget.type)?<p>No covered energy is available. Check the physical meter, recorded source, method and interval boundaries; unknown consumption is not zero.</p>:null}{ready?<><details><summary>Measurement fields and technical details</summary><Evidence value={primary}/></details>{freshness?<details><summary>Measurement age details</summary><Evidence value={freshness}/></details>:null}{sources?<details><summary>Owner source evidence</summary><Evidence value={sources}/></details>:null}<details><summary>Scope and provenance</summary><Evidence value={{devices,locations,...panel.provenance}}/></details></>:null}{panel?<section className="dashboard-info-summary"><h3>Read summary</h3><Evidence value={{...(panel.time?{from:panel.time.from,to:panel.time.to,timezone:panel.time.timezone}:{}),evaluatedEquipment:`${panel.evaluatedCount} of ${panel.requestedCount}`,receivedAt:panel.receivedAt,measuredAt:panel.measuredAt ?? null,age:`${Math.max(0,Math.floor((Date.now()-Date.parse(panel.receivedAt))/1000))} s`}}/></section>:null}{safeLink?<Link to={safeLink}>Open related view</Link>:null}</div>;}
  const quality=widget.type==="latest-value" && data?.value && typeof data.value==="object"?(data.value as Record<string,unknown>).quality:null;
  return <div className="dashboard-widget-content" aria-busy={!panel}>
    <button type="button" className="ghost-button dashboard-panel-info-button" title="Panel information" aria-label={`Information for ${widget.title}`} onClick={()=>setInfoOpen(true)}><Info size={15}/></button>
    {stale?<span className="dashboard-state-badge" role="status">Stale</span>:null}
    {!ready?<span className="dashboard-state-badge" role="status">{status}</span>:null}
    {quality && quality!=="OK"?<span className="dashboard-state-badge">Quality: {String(quality)}</span>:null}
    {ready?<DashboardBusinessEvidence widget={widget} panel={panel}/>:null}
    {widget.presentation?.showDetails===true?information():null}
    {panel && !["ready","no_data","unsupported"].includes(panel.status)?<button className="ghost-button" type="button" onClick={onRetry}>Retry {widget.title}</button>:null}
    {infoOpen?<Dialog title={`Information for ${widget.title}`} side onClose={()=>setInfoOpen(false)}>{information()}</Dialog>:null}
  </div>;
}
