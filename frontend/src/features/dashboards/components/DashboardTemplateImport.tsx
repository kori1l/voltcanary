import {useRef,useState} from "react";
import {useNavigate} from "react-router-dom";
import {apiRequest} from "../../../api/client";
import {commandKey,type DashboardMetadata} from "../../../dashboards/dashboardsApi";
export function DashboardTemplateImport(){
  const [source,setSource]=useState(""),[reviewed,setReviewed]=useState(false),[busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null);
  const lock=useRef(false),navigate=useNavigate();
  async function importTemplate(){if(lock.current || !reviewed)return;lock.current=true;setBusy(true);try{const definition=JSON.parse(source);const item=await apiRequest<DashboardMetadata>("/dashboards/import",{method:"POST",headers:{"Idempotency-Key":commandKey()},body:JSON.stringify({definition,reviewed})});navigate(`/dashboards/${item.id}/edit`);}catch(e){setError(e instanceof Error?e.message:"Import failed");}finally{lock.current=false;setBusy(false);}}
  return <section><p>Imported widgets require new resource bindings. Resource grants, telemetry, navigation and source references are removed.</p><label>Template JSON<textarea value={source} maxLength={500000} onChange={e=>{setSource(e.target.value);setReviewed(false);}}/></label><label><input type="checkbox" checked={reviewed} onChange={e=>setReviewed(e.target.checked)}/>I reviewed this template and will bind my authorized resources</label><button type="button" disabled={busy || !reviewed || !source} onClick={()=>void importTemplate()}>Import private draft</button>{error?<p role="alert">{error}</p>:null}</section>;
}
