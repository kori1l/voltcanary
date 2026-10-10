import {useRef,useState} from "react";
import {useNavigate} from "react-router-dom";
import {Dialog} from "../../../components/Dialog";
import {Field} from "../../../components/Field";
import {commandKey,createDashboard} from "../../../dashboards/dashboardsApi";
import {sessionIdentityRevision} from "../../../api/client";
import {markWorkflowDraftSaved} from "../../../components/workflowDraftContext";
import {blankDashboard} from "../model/dashboardLayout";
import {DashboardTemplateWizard} from "./DashboardTemplateWizard";
export function DashboardCreation({onClose}:{onClose:()=>void}){
 const navigate=useNavigate();const [mode,setMode]=useState("blank"),[name,setName]=useState(""),[description,setDescription]=useState(""),[busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null);
 const lock=useRef(false),key=useRef<{body:string;key:string}|null>(null);
 async function create(){if(lock.current || !name.trim())return;lock.current=true;setBusy(true);setError(null);const identity=sessionIdentityRevision();const body=JSON.stringify({name:name.trim(),description});if(key.current?.body!==body)key.current={body,key:commandKey()};
 try{const dashboard=await createDashboard(blankDashboard(name,description),key.current.key);if(identity===sessionIdentityRevision()){markWorkflowDraftSaved("dashboard-create");queueMicrotask(()=>navigate(`/dashboards/${dashboard.id}/edit`));}}catch(e){if(identity===sessionIdentityRevision())setError(e instanceof Error?e.message:"Creation failed");}finally{lock.current=false;setBusy(false);}}
 return <Dialog title="New dashboard" side busy={busy} onClose={onClose}><div className="action-row"><button className="ghost-button" aria-pressed={mode==="blank"} onClick={()=>setMode("blank")}>Blank dashboard</button><button className="ghost-button" aria-pressed={mode==="templates"} onClick={()=>setMode("templates")}>Templates</button></div>{error?<p role="alert">{error}</p>:null}{mode==="templates"?<DashboardTemplateWizard/>:<form data-draft-id="dashboard-create" onSubmit={(e)=>{e.preventDefault();void create();}}><Field label="Dashboard name" required>{(props)=><input {...props} required maxLength={80} value={name} onChange={(e)=>setName(e.target.value)}/>}</Field><Field label="Dashboard description">{(props)=><textarea {...props} maxLength={500} value={description} onChange={(e)=>setDescription(e.target.value)}/>}</Field><button className="primary-button" disabled={busy || !name.trim()}>{busy?"Creating...":"Create draft"}</button></form>}</Dialog>;
}
