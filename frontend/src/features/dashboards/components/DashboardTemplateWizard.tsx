import {useEffect,useRef,useState} from "react";
import {useNavigate} from "react-router-dom";
import {Dialog} from "../../../components/Dialog";
import {EntitySelector} from "../../../components/EntitySelector";
import {listDevices,type Device} from "../../../devices/devicesApi";
import {getTelemetryMetrics} from "../../../telemetry/telemetryApi";
import {commandKey,createDashboard,type DashboardDefinition} from "../../../dashboards/dashboardsApi";
import {sessionIdentityRevision} from "../../../api/client";
import {markWorkflowDraftSaved} from "../../../components/workflowDraftContext";
import {dashboardDeviceOptions,dashboardLocationOptions,refrigerationDeviceOptions} from "../model/dashboardEntityOptions";
import {instantiateTemplate,listDashboardTemplates,type DashboardTemplate} from "../model/dashboardTemplates";

export function DashboardTemplateWizard(){
  const navigate=useNavigate();const [templates,setTemplates]=useState<DashboardTemplate[]>([]);const [error,setError]=useState<string|null>(null);const [search,setSearch]=useState("");const [selected,setSelected]=useState<DashboardTemplate|null>(null);
  const [name,setName]=useState("");const [ids,setIds]=useState<string[]>(["","",""]);const [locationId,setLocationId]=useState("");const [devices,setDevices]=useState<Device[]>([]);const [missing,setMissing]=useState<string[]>([]);const [checked,setChecked]=useState(false);const [checking,setChecking]=useState(false);const [keepMissing,setKeepMissing]=useState(true);const [busy,setBusy]=useState(false);
  const key=useRef<{input:string;definition:DashboardDefinition;key:string}|null>(null);const lock=useRef(false);
  const bindingKey=JSON.stringify([selected?.id,ids,locationId]);
  const [independentMetersConfirmed,setIndependentMetersConfirmed]=useState(false);
  const currentBinding=useRef(bindingKey);currentBinding.current=bindingKey;
  useEffect(()=>{const controller=new AbortController();void listDashboardTemplates(controller.signal).then(setTemplates).catch((error)=>{if(!controller.signal.aborted)setError(error instanceof Error?error.message:"Template catalog unavailable");});return()=>controller.abort();},[]);
  useEffect(()=>{setChecked(false);setMissing([]);setDevices([]);setIndependentMetersConfirmed(false);},[bindingKey]);
  async function inspect(){
    if(!selected || checking)return;setChecking(true);setChecked(false);setError(null);const identity=sessionIdentityRevision();const expected=bindingKey;
    try{if(selected.id==="energy" && !independentMetersConfirmed)throw new Error("Review and confirm independent physical meters before checking this energy scope.");let offset=0;const permitted:Device[]=[];while(true){const page=await listDevices({locationId:locationId || undefined,type:selected.id==="refrigeration"?"FRIDGE":undefined,limit:100,offset});permitted.push(...page);if(page.length<100)break;offset+=100;if(offset>=500)throw new Error("Scope exceeds 500 devices. Select a narrower location.");}
      const chosen=ids.filter(Boolean);if(new Set(chosen).size!==chosen.length)throw new Error("Choose each device only once.");if(chosen.some((id)=>!permitted.some((device)=>device.id===id)))throw new Error("A binding is no longer authorized in this scope. Rebind using current choices.");
      const scoped=chosen.length?permitted.filter((device)=>chosen.includes(device.id)):permitted;
      if(!scoped.length)throw new Error("No authorized equipment in this scope.");
      const primary=chosen[0];const gaps:string[]=[];
      if(selected.panels.some((panel)=>panel.metric)){
        if(!primary)gaps.push(...selected.panels.filter((panel)=>panel.metric).map((panel)=>panel.key));
        else {const now=Date.now();const metricSets=await Promise.all((selected.id==="my-devices"?chosen:[primary]).map((id)=>getTelemetryMetrics(id,new Date(now-30*86400000).toISOString(),new Date(now).toISOString())));
          for(const panel of selected.panels)if(panel.metric){if(selected.id==="my-devices" && panel.type==="latest-value")metricSets.forEach((metrics,index)=>{if(!metrics.some((metric)=>metric.name===panel.metric && metric.unit===panel.unit))gaps.push(`${panel.key}:${chosen[index]}`);});else if(!metricSets[0].some((metric)=>metric.name===panel.metric && metric.unit===panel.unit))gaps.push(panel.key);}}
      }
      if(identity===sessionIdentityRevision() && expected===currentBinding.current){setDevices(scoped);setMissing(gaps);setChecked(true);}
    }catch(error){if(identity===sessionIdentityRevision())setError(error instanceof Error?error.message:"Binding check failed");}finally{setChecking(false);}
  }
  async function create(){if(!selected || !checked || lock.current || !name.trim())return;lock.current=true;setBusy(true);setError(null);const identity=sessionIdentityRevision();
    try{const binding={deviceIds:ids.filter(Boolean),deviceNames:Object.fromEntries(devices.map((device)=>[device.id,device.name])),locationId,missingKeys:missing,keepMissing,demo:devices.some((device)=>/^Demo\b/i.test(device.name)),independentMetersConfirmed};const input=JSON.stringify([selected,name,binding]);
      // The same reviewed snapshot/key survives a failed transport retry.
      if(key.current?.input!==input)key.current={input,definition:instantiateTemplate(selected,name,binding),key:commandKey()};
      const created=await createDashboard(key.current.definition,key.current.key);if(identity===sessionIdentityRevision()){markWorkflowDraftSaved("dashboard-template");queueMicrotask(()=>navigate(`/dashboards/${created.id}/edit`));}
    }catch(error){if(identity===sessionIdentityRevision())setError(error instanceof Error?error.message:"Template creation failed");}finally{lock.current=false;setBusy(false);}
  }
  return <section className="panel"><h2>Start from a business task</h2><p>Optional task guidance: Café, Store, Laundry, Property, Workshop, IT, Service or Home. These labels do not change your role or permissions.</p><label>Search task templates<input type="search" value={search} onChange={(event)=>setSearch(event.target.value)} maxLength={100}/></label>
    {error?<p role="alert">{error}</p>:null}<div className="dashboard-template-catalog">{templates.filter((template)=>`${template.title} ${template.task} ${template.persona} ${template.segments.join(" ")}`.toLowerCase().includes(search.toLowerCase())).map((template)=><article key={`${template.id}-${template.version}`}><h3>{template.title} · v{template.version}</h3><div className="dashboard-template-thumbnail" aria-label={`${template.title} layout preview`}>{template.panels.map((panel)=><span key={panel.key}>{panel.title}</span>)}</div><p>{template.task} · {template.persona} · {template.period}</p><p>{template.explanation}</p><button className="ghost-button" disabled={!template.available || template.version!==1} onClick={()=>{setSelected(template);setName(template.title);setIds(["","",""]);setLocationId("");setError(null);key.current=null;}}>Bind {template.title}</button>{template.version!==1?<p>This version is unsupported; use an available version 1 snapshot or start blank.</p>:null}</article>)}</div>
    {selected?<Dialog title={`Bind ${selected.title}`} busy={busy} wide onClose={()=>{setSelected(null);setError(null);}}><form data-draft-id="dashboard-template" onSubmit={(event)=>{event.preventDefault();void create();}}><label>Template dashboard name<input required maxLength={80} value={name} onChange={(event)=>setName(event.target.value)}/></label>
      <EntitySelector label="Template location" options={[]} value={locationId} loadOptions={dashboardLocationOptions} onChange={setLocationId} disabled={busy || checking}/>
      {ids.map((id,index)=><EntitySelector key={index} label={`Equipment slot ${index+1}${index===0?" (primary measurement)":""}`} options={[]} value={id} loadOptions={selected.id==="refrigeration"?refrigerationDeviceOptions:dashboardDeviceOptions} disabled={busy || checking} onChange={(id)=>{setIds((previous)=>previous.map((value,at)=>at===index?id:value));}}/>)}
      {selected.id==="my-devices" && ids.length<6?<button type="button" className="ghost-button" onClick={()=>setIds([...ids,""])}>Add equipment slot</button>:null}
      {selected.id==="energy"?<><label><input type="checkbox" checked={independentMetersConfirmed} onChange={(event)=>{setIndependentMetersConfirmed(event.target.checked);setChecked(false);}}/>I reviewed the independent physical meters in this scope</label><p>Select one upstream meter or its independent children. Missing metering remains partial; duplicate identities and known parent/submeter overlap are rejected. Configure each meter from its energy widget before publication.</p></>:null}
      <button type="button" className="ghost-button" disabled={checking || busy} onClick={()=>void inspect()}>{checking?"Checking bindings…":"Check current bindings and capabilities"}</button>
      {checked?<><p role="status">Resolved scope: {devices.length} authorized devices · {selected.period}. {devices.some((device)=>/^Demo\b/i.test(device.name))?"DEMO equipment selected; recorded values remain labeled.":""}</p><p>Assignment, tariff and confirmed cycle data are unavailable. Refrigeration defaults use FRIDGE equipment; only the reviewed scope is used.</p>{missing.length?<><p role="alert">Missing registered metric or canonical unit for: {selected.panels.filter((panel)=>missing.some((key)=>key===panel.key || key.startsWith(`${panel.key}:`))).map((panel)=>`${panel.title} (${panel.metric} · ${panel.unit})`).join(", ")}. No measurement will be fabricated.</p><label>Unavailable panels<select value={keepMissing?"keep":"remove"} onChange={(event)=>setKeepMissing(event.target.value==="keep")}><option value="keep">Keep annotated and unbound</option><option value="remove">Remove unavailable panels</option></select></label></>:null}<p>Creates an independent v{selected.version} draft. Review real saved-draft evidence in Edit before publishing.</p></>:null}
      <button type="submit" className="primary-button" disabled={!checked || busy || !name.trim()}>{busy?"Creating…":"Create template draft and preview"}</button>
    </form></Dialog>:null}</section>;
}



