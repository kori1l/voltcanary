import {useEffect, useRef, useState, useSyncExternalStore} from "react";
import {ApiError, isAuthenticated, sessionIdentityRevision, subscribeSession} from "../api/client";
import {executeAgentPlan, getAgentPlan, getDashboardTask, type AgentPlan, type ExecutePlanResponse, type DashboardTaskSummary} from "./aiAgentApi";
import {lastAssistantTask, rememberAssistantTask, lastDashboardTask, rememberDashboardTask, forgetDashboardTask} from "./assistantTaskRecovery";
import {controlOrchestration,conversationMessages, createConversation, getConversation, listConversations, selectConversation, submitConversationMessage, type ConversationDetail, type OrchestrationProgress, type SavedConversation, type SubmittedMessage} from "./conversationApi";
import {readConversationDraft, saveConversationDraft} from "./conversationDrafts";
import {planMutates} from "./PlanReview";
import type {AssistantPhase} from "./AssistantProgress";

export interface ConversationTurn {id:string;role:"user"|"assistant";text:string;planId?:string;turnId?:string;automatic?:boolean;redacted?:boolean;kind?:string;outcome?:string}
interface Conversation {id:string;title:string;turns:ConversationTurn[];plan:AgentPlan|null;draft:string;context:Record<string,unknown>;revision:number;saved:boolean;allowAutoReply?:boolean;pendingStatus?:string|null;resourceStatus?:string;task?:DashboardTaskSummary;retainedPreview?:AgentPlan;autoAnsweredFields?:string[];reviewIds?:string[];reviewIndex?:number;orchestration?:OrchestrationProgress}
function dashboardReview(plan:AgentPlan){return plan.actions.some(action=>["CREATE_DASHBOARD","UPDATE_DASHBOARD_DRAFT"].includes(action.tool)&&action.parameters.definition)&&plan.planningStatus!=="NEEDS_INPUT";}
function reviewSelection(plan:AgentPlan,previous?:Conversation){
  const inherited=plan.previousReviewIds??(previous?.reviewIds??[]).slice(0,(previous?.reviewIndex??(previous?.reviewIds?.length??0)-1)+1);
  const reviewIds=[...new Set([...inherited,...(dashboardReview(plan)?[plan.planId]:[])])];
  return {reviewIds,reviewIndex:reviewIds.length-1};
}
const fresh=():Conversation=>({id:crypto.randomUUID(),title:"Новый разговор",turns:[],plan:null,draft:readConversationDraft("new"),context:{},revision:0,saved:false,allowAutoReply:false});

export function useServerConversation() {
  const identity=useSyncExternalStore(subscribeSession,sessionIdentityRevision,sessionIdentityRevision);
  const [conversation,setConversation]=useState<Conversation>(fresh);
  const [history,setHistory]=useState<SavedConversation[]>([]),[historyCursor,setHistoryCursor]=useState<string|null>(null);
  const [busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null),[draftSaved,setDraftSaved]=useState(true);
  const [phase,setPhase]=useState<AssistantPhase>("planning");
  const [activeTurnId,setActiveTurnId]=useState<string|null>(null);
  const current=useRef(conversation);current.current=conversation;
  const generation=useRef(0),pending=useRef(false),mounted=useRef(true);
  const retry=useRef<{fingerprint:string;body:SubmittedMessage}|null>(null);
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;generation.current++;};},[]);
  function active(version:number,actor:number){return mounted.current&&version===generation.current&&actor===sessionIdentityRevision();}
  async function hydrate(detail:ConversationDetail,previous?:Conversation):Promise<Conversation> {
    const saved=detail.conversation,messages=await conversationMessages(saved.id);
    let plan=detail.plan;
    const reviews:Pick<Conversation,"reviewIds"|"reviewIndex">=plan?reviewSelection(plan,previous):{};
    const pointer=lastDashboardTask();
    if(!previous&&plan?.task&&pointer?.selectedReviewId&&pointer.taskId===plan.task.taskId&&pointer.branchId===plan.task.branchId&&plan.executionStatus==="READY"){
      try{
        const selected=await getAgentPlan(pointer.selectedReviewId);
        if(dashboardReview(selected)&&selected.task?.taskId===pointer.taskId&&selected.task.branchId===pointer.branchId&&selected.executionStatus==="READY"){
          plan=selected;reviews.reviewIndex=reviews.reviewIds?.indexOf(selected.planId)??0;
        }
      }catch{/* The latest server review remains available if an old snapshot expired. */}
    }
    const retainedPreview=plan&&dashboardReview(plan)?undefined:detail.acceptedReview&&dashboardReview(detail.acceptedReview)?detail.acceptedReview:previous?.retainedPreview??(previous?.plan&&dashboardReview(previous.plan)?previous.plan:undefined);
    return {...fresh(),...(previous??{}),id:saved.id,title:saved.title,revision:saved.revision,saved:true,
      turns:messages.map(message=>({...message,planId:message.planId??undefined})),plan,task:plan?.task??undefined,
      draft:readConversationDraft(saved.id),resourceStatus:detail.resourceStatus,pendingStatus:detail.pendingStatus,orchestration:detail.orchestration,
      retainedPreview,allowAutoReply:false,...reviews};
  }
  async function refreshHistory(version:number,actor:number,before?:string) {
    const result=await listConversations(before);
    if(active(version,actor)){setHistory(items=>before?[...items,...result.conversations.items.filter(item=>!items.some(old=>old.id===item.id))]:result.conversations.items);setHistoryCursor(result.conversations.nextCursor);}
    return result;
  }
  useEffect(()=>{
    const version=++generation.current,actor=sessionIdentityRevision();pending.current=false;retry.current=null;
    setConversation(fresh());setHistory([]);setHistoryCursor(null);setError(null);setDraftSaved(true);setActiveTurnId(null);
    if(!isAuthenticated()){setBusy(false);return;}
    pending.current=true;setBusy(true);setPhase("recovering");
    void (async()=>{
      try {
        const result=await refreshHistory(version,actor);
        if(result.activeConversationId&&active(version,actor)){
          const restored=await hydrate(await getConversation(result.activeConversationId));
          if(active(version,actor))setConversation(restored);
        }
      }catch(failure){if(active(version,actor))setError(failure instanceof Error?failure.message:"История недоступна. Обновите разговор.");}
      finally{if(active(version,actor)){pending.current=false;setBusy(false);}}
    })();
  },[identity]);
  function patch(values:Partial<Conversation>){
    if(values.draft!==undefined)setDraftSaved(saveConversationDraft(current.current.saved?current.current.id:"new",values.draft));
    setConversation(previous=>({...previous,...values}));
  }
  async function newConversation(context:Record<string,unknown>={},draft="") {
    if(pending.current)return;
    const version=++generation.current,actor=sessionIdentityRevision();retry.current=null;
    const next={...fresh(),draft,context};setConversation(next);setError(null);saveConversationDraft("new",draft);
    if(!isAuthenticated())return;
    pending.current=true;setBusy(true);
    try {
      const saved=await createConversation(next.id);
      if(active(version,actor)){saveConversationDraft(saved.id,draft);setConversation({...next,saved:true,revision:saved.revision});await refreshHistory(version,actor);}
    }catch(failure){if(active(version,actor))setError(failure instanceof Error?failure.message:"Не удалось сохранить разговор.");}
    finally{if(active(version,actor)){pending.current=false;setBusy(false);}}
  }
  async function select(id:string) {
    if(pending.current)return;
    const version=++generation.current,actor=sessionIdentityRevision();pending.current=true;setBusy(true);setPhase("recovering");setError(null);
    try {
      await selectConversation(id);const restored=await hydrate(await getConversation(id));
      if(active(version,actor)){retry.current=null;setConversation(restored);}
    }catch(failure){if(active(version,actor))setError(failure instanceof Error?failure.message:"Разговор недоступен.");}
    finally{if(active(version,actor)){pending.current=false;setBusy(false);}}
  }
  async function reload() {
    if(pending.current)return;
    const previous=current.current,version=++generation.current,actor=sessionIdentityRevision();pending.current=true;setBusy(true);setPhase("recovering");
    try {
      const history=await refreshHistory(version,actor);
      const id=previous.saved?previous.id:history.activeConversationId;
      if(id){const restored=await hydrate(await getConversation(id),previous.saved?previous:undefined);if(active(version,actor))setConversation(restored);}
      if(active(version,actor))setError(null);
    }catch(failure){if(active(version,actor))setError(failure instanceof Error?failure.message:"Не удалось обновить разговор.");}
    finally{if(active(version,actor)){pending.current=false;setBusy(false);}}
  }
  useEffect(()=>{
    if(conversation.pendingStatus!=="PENDING")return;
    const timer=window.setInterval(()=>void reload(),3000);return()=>window.clearInterval(timer);
  },[conversation.id,conversation.pendingStatus]);
  async function send(override?:string,automatic=false,displayText?:string){
    let previous=current.current;
    const text=(override??previous.draft).trim();if(!text||text.length>2000||pending.current||previous.pendingStatus==="PENDING")return;
    const version=++generation.current,actor=sessionIdentityRevision();pending.current=true;setPhase("planning");setBusy(true);setError(null);
    try {
      if(!previous.saved){
        const saved=await createConversation(previous.id);if(!active(version,actor))return;
        saveConversationDraft(saved.id,previous.draft);previous={...previous,saved:true,revision:saved.revision};setConversation(previous);
      }
      const status=previous.plan?.execution?.status??previous.plan?.executionStatus;
      // A failed preparation still owns the user's pending intent; a short reply must keep that task.
      const continuation=previous.plan&&previous.resourceStatus!=="UNAVAILABLE"&&!["SUCCESS","FAILED","PARTIAL_SUCCESS","RUNNING","UNKNOWN","EXPIRED"].includes(status??"");
      const fingerprint=JSON.stringify([previous.id,previous.plan?.planId,text,previous.context,automatic]);
      if(retry.current?.fingerprint!==fingerprint){
        const task=!previous.plan||continuation?previous.task??previous.plan?.task:undefined;
        const taskId=task?.taskId??crypto.randomUUID(),requestId=crypto.randomUUID();
        retry.current={fingerprint,body:{requestId,expectedRevision:previous.revision,text,context:previous.context,
          ...(continuation?{parentPlanId:previous.plan!.planId}:{}),automatic,displayText,
          metadata:{requestId,taskId,branchId:task?.branchId??taskId,expectedTaskRevision:task?.revision??0,acceptedSuggestion:automatic}}};
      }
      setActiveTurnId(retry.current.body.requestId);
      const response=await submitConversationMessage(previous.id,retry.current.body);if(!active(version,actor))return;
      if(response.pendingStatus!=="PENDING")retry.current=null;
      if(response.pendingStatus==="COMPLETED"){saveConversationDraft(previous.id,"");saveConversationDraft("new","");}
      const restored=await hydrate(response,previous);if(!active(version,actor))return;
      restored.allowAutoReply=response.pendingStatus==="COMPLETED";
      restored.autoAnsweredFields=automatic?[...new Set([...(previous.autoAnsweredFields??[]),...(previous.plan?.clarification?.missingFields??[])])]:previous.autoAnsweredFields;
      setConversation(restored);
      if(response.pendingStatus==="FAILED"||response.pendingStatus==="UNKNOWN")setError("Сообщение сохранено. Ответ не подготовлен; можно отправить новое сообщение.");
      const plan=response.plan;
      if(plan){rememberAssistantTask(plan.planId);if(plan.task)rememberDashboardTask(plan.task.taskId,plan.task.branchId);else forgetDashboardTask();}
      if(plan&&response.pendingStatus==="COMPLETED"&&plan.actions.length&&!planMutates(plan)&&plan.planningStatus!=="NEEDS_INPUT"&&!plan.execution){
        setPhase("reading");await executeAgentPlan(plan.planId,false,plan.version,plan.payloadDigest);
        const completed=await hydrate(await getConversation(previous.id),restored);if(active(version,actor))setConversation(completed);
      }
      await refreshHistory(version,actor);
    }catch(failure){if(active(version,actor)){
      setError(failure instanceof Error?failure.message:"Не удалось получить ответ. Повторите сообщение с тем же запросом.");
      if(failure instanceof ApiError&&failure.status===409){
        retry.current=null;
        try{const restored=await hydrate(await getConversation(previous.id),previous);if(active(version,actor))setConversation(restored);}catch{/* Keep original error. */}
      }
    }}finally{if(active(version,actor)){pending.current=false;setBusy(false);setActiveTurnId(null);}}
  }
  async function recover(){
    const id=lastAssistantTask(),pointer=lastDashboardTask();if((!id&&!pointer)||pending.current)return;
    const version=++generation.current,actor=sessionIdentityRevision();pending.current=true;setPhase("recovering");setBusy(true);setError(null);
    try {
      const recovered=pointer?await getDashboardTask(pointer.taskId,pointer.branchId):null;
      const plan=recovered?.lastReview??(id?await getAgentPlan(id):null),task=recovered?.task??plan?.task??undefined;
      if(active(version,actor))setConversation({...fresh(),title:"Восстановленная задача",plan,task,...(plan?reviewSelection(plan):{}),
        turns:[{id:crypto.randomUUID(),role:"assistant",text:"Восстановлена сохранённая задача. Переписка до включения истории недоступна.",planId:plan?.planId}]});
    }catch(failure){if(active(version,actor))setError(failure instanceof Error?failure.message:"Задача недоступна");}
    finally{if(active(version,actor)){pending.current=false;setBusy(false);}}
  }
  async function selectReview(index:number){
    const previous=current.current,id=previous.reviewIds?.[index];if(!id||pending.current||previous.plan?.execution)return;
    const version=++generation.current,actor=sessionIdentityRevision();pending.current=true;setPhase("recovering");setBusy(true);setError(null);
    try {
      const plan=await getAgentPlan(id);
      if(active(version,actor)){
        if(!dashboardReview(plan)||![undefined,"READY",...(previous.orchestration?["SUPERSEDED"]:[])].includes(plan.executionStatus))throw new Error("Этот вариант больше недоступен для редактирования.");
        rememberAssistantTask(plan.planId);retry.current=null;if(plan.task)rememberDashboardTask(plan.task.taskId,plan.task.branchId,plan.planId);
        setConversation({...previous,plan,task:plan.task??undefined,retainedPreview:undefined,reviewIndex:index});
      }
    }catch(failure){if(active(version,actor))setError(failure instanceof Error?failure.message:"Не удалось открыть вариант.");}
    finally{if(active(version,actor)){pending.current=false;setBusy(false);}}
  }
  function executed(execution:ExecutePlanResponse){
    setConversation(value=>value.plan?.planId===execution.planId?{...value,plan:{...value.plan,execution,executionStatus:execution.status}}:value);void reload();
  }
  async function control(action:"CANCEL"|"RETRY"|"RESUME",stepId:string|null=null){
    const previous=current.current,version=++generation.current,actor=sessionIdentityRevision();
    pending.current=true;setBusy(true);setError(null);retry.current=null;
    try{
      await controlOrchestration(previous.id,action,stepId);
      const restored=await hydrate(await getConversation(previous.id),previous);
      if(active(version,actor))setConversation(restored);
    }catch(failure){if(active(version,actor))setError(failure instanceof Error?failure.message:"Не удалось изменить состояние плана.");}
    finally{if(active(version,actor)){pending.current=false;setBusy(false);setActiveTurnId(null);}}
  }
  async function moreHistory(){const version=generation.current,actor=sessionIdentityRevision();if(historyCursor&&!pending.current)try{await refreshHistory(version,actor,historyCursor);}catch(failure){if(active(version,actor))setError(failure instanceof Error?failure.message:"История недоступна.");}}
  const ids=conversation.reviewIds??[],selected=conversation.reviewIndex??ids.length-1,reviewing=ids[selected]===conversation.plan?.planId;
  return {conversation,history,busy,phase,activeTurnId,error,draftSaved,patch,send,recover,reload,newConversation,select,executed,selectReview,control,moreHistory,hasMoreHistory:!!historyCursor,
    undoIndex:reviewing?selected-1:selected,redoIndex:reviewing&&selected+1<ids.length?selected+1:-1,canRecover:Boolean(lastAssistantTask()||lastDashboardTask())};
}
