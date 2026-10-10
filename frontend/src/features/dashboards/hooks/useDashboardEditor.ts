import { useEffect, useRef, useState } from "react";
import { ApiError, sessionIdentityRevision } from "../../../api/client";
import { commandKey, getDashboardDraft, publishDashboard, saveDashboardDraft, type DashboardDefinition, type DashboardDraft, type DashboardMetadata } from "../../../dashboards/dashboardsApi";
import { useWorkflowDraft } from "../../../components/workflowDraftContext";
import { clearRecovery, readRecovery, writeRecovery } from "../model/dashboardRecovery";

export function useDashboardEditor(metadata:DashboardMetadata, initial:DashboardDraft) {
  const [definition,setDefinition]=useState(initial.definition);
  const [draft,setDraft]=useState(initial);
  const [status,setStatus]=useState("Saved");
  const [conflict,setConflict]=useState(false);
  const [denied,setDenied]=useState(false);
  const [latestConflict,setLatestConflict]=useState<DashboardDraft|null>(null);
  const [recovery,setRecovery]=useState(()=>readRecovery(metadata,initial.baseRevision));
  const [history,setHistory]=useState<{past:DashboardDefinition[];future:DashboardDefinition[]}>({past:[],future:[]});
  const current=useRef(definition);current.current=definition;
  const canonical=useRef(initial);const alive=useRef(true);const identity=useRef(sessionIdentityRevision());
  const lock=useRef<Promise<DashboardDraft|null>|null>(null);const publishing=useRef(false);
  const publishKey=useRef<{etag:string;key:string}|null>(null);
  const saved=useRef(JSON.stringify(initial.definition));
  const dirty=JSON.stringify(definition)!==saved.current;
  const valid=()=>alive.current && identity.current===sessionIdentityRevision();
  useEffect(()=>{alive.current=true;return()=>{alive.current=false;};},[]);
  function discard(){clearRecovery(metadata,canonical.current.baseRevision);setDefinition(canonical.current.definition);setRecovery(null);setHistory({past:[],future:[]});}
  useWorkflowDraft(dirty,discard);
  function edit(next:DashboardDefinition){if(publishing.current || denied)return;if(JSON.stringify(next)===JSON.stringify(current.current))return;const before=current.current;current.current=next;setHistory((previous)=>({past:[...previous.past,before].slice(-20),future:[]}));setDefinition(next);setStatus(JSON.stringify(next)===saved.current?"Saved":"Unsaved");writeRecovery(metadata,canonical.current.baseRevision,next);}
  function undo(){const prior=history.past[history.past.length-1];if(!prior)return;setHistory({past:history.past.slice(0,-1),future:[definition,...history.future].slice(0,20)});setDefinition(prior);if(JSON.stringify(prior)===saved.current)clearRecovery(metadata,canonical.current.baseRevision);else writeRecovery(metadata,canonical.current.baseRevision,prior);setStatus(JSON.stringify(prior)===saved.current?"Saved":"Unsaved");}
  function redo(){const next=history.future[0];if(!next)return;setHistory({past:[...history.past,definition].slice(-20),future:history.future.slice(1)});setDefinition(next);if(JSON.stringify(next)===saved.current)clearRecovery(metadata,canonical.current.baseRevision);else writeRecovery(metadata,canonical.current.baseRevision,next);setStatus(JSON.stringify(next)===saved.current?"Saved":"Unsaved");}
  async function save():Promise<DashboardDraft|null>{
    if(lock.current)return lock.current;
    if(conflict || denied || !valid())return null;
    const snapshot=current.current;
    if(JSON.stringify(snapshot)===saved.current)return canonical.current;
    setStatus("Saving");
    const operation=(async()=>{try {
      const response=await saveDashboardDraft(canonical.current,snapshot);
      if(!valid())return null;
      canonical.current=response;saved.current=JSON.stringify(snapshot);setDraft(response);
      const same=JSON.stringify(current.current)===saved.current;
      setStatus(same?"Saved":"Unsaved");
      if(same)clearRecovery(metadata,response.baseRevision);else writeRecovery(metadata,response.baseRevision,current.current);
      return response;
    }catch(error){if(valid()){setStatus(error instanceof Error?`Save failed: ${error.message}`:"Save failed");if(error instanceof ApiError && error.status===409)setConflict(true);if(error instanceof ApiError && [401,403,404].includes(error.status)){clearRecovery(metadata,canonical.current.baseRevision);setRecovery(null);setDenied(true);}}return null;}finally{lock.current=null;}})();
    lock.current=operation;return operation;
  }
  useEffect(()=>{if(!dirty || conflict || denied || recovery || publishing.current)return;const timer=window.setTimeout(()=>void save(),2000);return()=>window.clearTimeout(timer);},[definition,draft.etag,conflict,denied,recovery,dirty]);
  useEffect(()=>{if(!conflict)return;const controller=new AbortController();void getDashboardDraft(metadata.id,controller.signal).then((latest)=>{if(valid() && !controller.signal.aborted)setLatestConflict(latest);}).catch(()=>{});return()=>controller.abort();},[conflict,metadata.id]);
  async function publish(){
    if(publishing.current || conflict || denied || recovery)return false;
    publishing.current=true;setStatus("Publishing");
    try {
      if(lock.current)await lock.current;
      const latest=await save();if(!latest || !valid())return false;
      if(!publishKey.current || publishKey.current.etag!==latest.etag)publishKey.current={etag:latest.etag,key:commandKey()};
      await publishDashboard(latest,publishKey.current.key);
      if(!valid())return false;
      const fresh=await getDashboardDraft(metadata.id);if(!valid())return false;
      canonical.current=fresh;saved.current=JSON.stringify(fresh.definition);setDefinition(fresh.definition);setDraft(fresh);setHistory({past:[],future:[]});clearRecovery(metadata,latest.baseRevision);setStatus("Published");publishKey.current=null;return true;
    }catch(error){if(valid()){setStatus(error instanceof Error?`Publish failed: ${error.message}`:"Publish failed");if(error instanceof ApiError && error.status===409)setConflict(true);if(error instanceof ApiError && [401,403,404].includes(error.status)){clearRecovery(metadata,canonical.current.baseRevision);setDenied(true);}}return false;}finally{publishing.current=false;}
  }
  async function resolve(keepLocal:boolean){
    if(lock.current || publishing.current || denied || (keepLocal && !latestConflict))return;
    try{const fresh=await getDashboardDraft(metadata.id);if(!valid())return;
      if(keepLocal && latestConflict?.etag!==fresh.etag){setLatestConflict(fresh);setStatus("Latest draft changed again. Review comparison before reapplying.");return;}
      clearRecovery(metadata,canonical.current.baseRevision);canonical.current=fresh;saved.current=JSON.stringify(fresh.definition);setDraft(fresh);setConflict(false);setLatestConflict(null);setRecovery(null);setHistory({past:[],future:[]});
      if(!keepLocal)setDefinition(fresh.definition);else writeRecovery(metadata,fresh.baseRevision,current.current);
      setStatus(keepLocal?"Unsaved · reviewed reapplication":"Saved");
    }catch(error){if(valid())setStatus(error instanceof Error?error.message:"Latest draft unavailable");}
  }
  return {definition,draft,status,dirty,conflict,denied,latestConflict,recovery,edit,save,publish,resolve,discard,undo,redo,canUndo:history.past.length>0,canRedo:history.future.length>0,
    restoreRecovery:()=>{if(recovery){edit(recovery);setRecovery(null);}},dismissRecovery:()=>{clearRecovery(metadata,draft.baseRevision);setRecovery(null);}};
}
