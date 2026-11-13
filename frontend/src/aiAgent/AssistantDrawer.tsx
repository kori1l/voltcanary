import {AssistantReviewHistory} from "./AssistantReviewHistory";
import {AssistantPlanProgress} from "./AssistantPlanProgress";
import {AssistantTurnTrace,AssistantTraceProgress} from "./AssistantTurnTrace";
import {DashboardCreationPreview} from "./DashboardCreationPreview";
import {ArrowUp, Check, ChevronDown, History, Maximize2, Minus, PanelRight, PictureInPicture2, Plus, Sparkles, SquarePen, X} from "lucide-react";
import {useEffect, useRef, useState} from "react";
import {useLocation} from "react-router-dom";
import {listDevices, type Device} from "../devices/devicesApi";
import {PlanReview} from "./PlanReview";
import {useAssistantConversation} from "./useAssistantConversation";
import {assistantDefaultReply} from "./assistantDefaultReply";
import {useAssistantAutoReply} from "./useAssistantAutoReply";
import {readAssistantWindow, saveAssistantWindow, type AssistantWindowMode, type AssistantWindowPosition} from "./assistantWindowState";
import "./assistant.css";
import {buildAssistantContext} from "./assistantContext";

const suggestions=["Какие устройства требуют внимания?","Объясни последнее срабатывание правила","Создай личный дашборд для устройства"];
export function AssistantDrawer() {
  const location=useLocation();
  const [open,setOpen]=useState(location.pathname==="/ai-agent");
  const [preferences,setPreferences]=useState(readAssistantWindow);
  const [menu,setMenu]=useState<"layout"|"history"|"context"|null>(null);
  const [devices,setDevices]=useState<Device[]>([]),[deviceError,setDeviceError]=useState<string|null>(null);
  const [executing,setExecuting]=useState(false);
  const assistant=useAssistantConversation(),{conversation,busy}=assistant;
  const locked=busy||executing||conversation.pendingStatus==="PENDING";
  const repeatsDefault=conversation.plan?.clarification?.missingFields.some(field=>conversation.autoAnsweredFields?.includes(field));
  const defaultReply=!conversation.allowAutoReply||repeatsDefault||conversation.task?.status==="FAILED"||conversation.task?.status==="PENDING"?null:assistantDefaultReply(conversation.plan);
  const autoReply=useAssistantAutoReply(conversation.plan?.planId,defaultReply,locked,message=>{if(!conversation.draft.trim())void assistant.send(message,true,defaultReply?.label);});
  const container=useRef<HTMLElement>(null),input=useRef<HTMLTextAreaElement>(null);
  const content=useRef<HTMLDivElement>(null);
  const launcher=useRef<HTMLButtonElement>(null),wasOpen=useRef(open);
  const headingId="assistant-window-title";
  useEffect(()=>{const show=()=>setOpen(true);window.addEventListener("voltcanary:open-assistant",show);return()=>window.removeEventListener("voltcanary:open-assistant",show);},[]);
  useEffect(()=>{
    if(location.pathname!=="/ai-agent")return;
    setOpen(true);
    const params=new URLSearchParams(location.search),deviceId=params.get("deviceId");
    if(params.get("workflow")==="dashboard"&&deviceId)assistant.newConversation({deviceId},"Создай личный дашборд для выбранного устройства. Предложи подходящий шаблон и уточни имя перед созданием.");
  },[location.pathname,location.search]);
  useEffect(()=>{if(open)input.current?.focus();else if(wasOpen.current)launcher.current?.focus();wasOpen.current=open;},[open]);
  useEffect(()=>{if(content.current)content.current.scrollTop=content.current.scrollHeight;},[conversation.turns.length]);
  useEffect(()=>{if(!open)return;const escape=(event:KeyboardEvent)=>{if(event.key!=="Escape"||event.defaultPrevented||document.querySelector("dialog[open]"))return;if(menu)setMenu(null);else setOpen(false);};document.addEventListener("keydown",escape);return()=>document.removeEventListener("keydown",escape);},[open,menu]);
  useEffect(()=>{if(!menu)return;const outside=(event:PointerEvent)=>{if(!container.current?.contains(event.target as Node))setMenu(null);};document.addEventListener("pointerdown",outside);return()=>document.removeEventListener("pointerdown",outside);},[menu]);
  useEffect(()=>{if(menu!=="context")return;const controller=new AbortController();setDeviceError(null);void listDevices({status:"ACTIVE",limit:100},controller.signal).then(setDevices).catch(error=>{if(!controller.signal.aborted)setDeviceError(error instanceof Error?error.message:"Список устройств недоступен");});return()=>controller.abort();},[menu]);
  function presentation(mode:AssistantWindowMode,position:AssistantWindowPosition=preferences.position){const next={mode,position:mode==="sidebar"&&position==="center"?"right" as const:position};setPreferences(next);saveAssistantWindow(next);}
  return <>
    <button ref={launcher} className="assistant-button" type="button" aria-label="AI" onClick={()=>setOpen(true)} hidden={open}><Sparkles size={21} aria-hidden="true"/><span className="sr-only">Открыть помощника</span></button>
    <section ref={container} hidden={!open} role="dialog" aria-modal="false" aria-labelledby={headingId} className={`assistant-window assistant-window-${preferences.mode} assistant-position-${preferences.position}`}>
      <header className="assistant-window-header"><div className="assistant-window-title"><Sparkles size={18} aria-hidden="true"/><button type="button" id={headingId} aria-label="История разговоров" onClick={()=>setMenu(menu==="history"?null:"history")} aria-expanded={menu==="history"}><span>{conversation.title}</span><ChevronDown size={14}/></button></div>
        <div className="assistant-window-tools"><button type="button" className="ghost-button" disabled={locked} aria-label="Новый разговор" title="Новый разговор" onClick={()=>{assistant.newConversation();setMenu(null);}}><SquarePen size={17}/></button><button type="button" className="ghost-button" aria-label="Вид и расположение" aria-expanded={menu==="layout"} title="Вид и расположение" onClick={()=>setMenu(menu==="layout"?null:"layout")}><PanelRight size={17}/></button><button type="button" className="ghost-button" aria-label="Свернуть помощника" title="Свернуть помощника" onClick={()=>{setOpen(false);setMenu(null);}}><Minus size={18}/></button></div>
        {menu==="layout"?<div className="assistant-window-menu" aria-label="Вид помощника"><small>Вид помощника</small>{([["sidebar","Боковая панель",PanelRight],["floating","Плавающее окно",PictureInPicture2],["expanded","Развернуть",Maximize2]] as const).map(([mode,label,Icon])=><button type="button" key={mode} aria-pressed={preferences.mode===mode} onClick={()=>{presentation(mode);setMenu(null);}}><Icon size={16}/>{label}{preferences.mode===mode?<Check size={14}/>:null}</button>)}<hr/><small>Расположение</small><div className="assistant-position-options">{([["left","Слева"],["center","По центру"],["right","Справа"]] as const).map(([position,label])=><button type="button" key={position} aria-pressed={preferences.position===position} disabled={preferences.mode==="expanded"||(preferences.mode==="sidebar"&&position==="center")} onClick={()=>presentation(preferences.mode,position)}>{label}</button>)}</div></div>:null}
        {menu==="history"?<div className="assistant-window-menu assistant-history-menu"><small>Разговоры</small>{assistant.history.map(item=><button type="button" key={item.id} disabled={busy||executing} onClick={()=>{void assistant.select(item.id);setMenu(null);}}><History size={15}/>{item.title}</button>)}{assistant.hasMoreHistory?<button type="button" disabled={busy} onClick={()=>void assistant.moreHistory()}>Загрузить ещё</button>:null}{!assistant.history.length?<p>Предыдущих разговоров пока нет.</p>:null}{assistant.canRecover?<button type="button" disabled={locked} onClick={()=>{void assistant.recover();setMenu(null);}}>Восстановить последнюю задачу</button>:null}</div>:null}
      </header>
      <div ref={content} className="assistant-window-content">
        {!conversation.turns.length?<div className="assistant-welcome"><span className="assistant-welcome-mark"><Sparkles size={25}/></span><h2>Чем могу помочь?</h2><div>{suggestions.map(prompt=><button type="button" key={prompt} onClick={()=>{assistant.patch({draft:prompt});input.current?.focus();}}>{prompt}</button>)}</div></div>:<div className="assistant-conversation" aria-label="Разговор с помощником">{conversation.turns.map((turn,index)=><div key={turn.id} className={`assistant-turn assistant-turn-${turn.role}`}>
          {turn.role==="assistant"?<div className="assistant-answer-label"><Sparkles size={14}/>VoltCanary</div>:null}
          {turn.automatic?<small className="assistant-auto-label">Выбрано автоматически после минуты ожидания</small>:null}
          <p>{turn.text}</p>{turn.redacted?<small>Секретные значения скрыты</small>:null}
          {turn.role==="user"&&turn.turnId&&conversation.saved?<AssistantTurnTrace conversationId={conversation.id} turnId={turn.turnId} pending={conversation.pendingStatus==="PENDING"}/>:null}
          {turn.role==="assistant"&&index===conversation.turns.length-1&&conversation.plan&&conversation.resourceStatus!=="UNAVAILABLE"?<><AssistantReviewHistory ids={conversation.reviewIds??[]} index={conversation.reviewIndex??0} undoIndex={assistant.undoIndex} redoIndex={assistant.redoIndex} disabled={locked||!!conversation.plan.execution||["SUCCESS","RUNNING","UNKNOWN","PARTIAL_SUCCESS"].includes(conversation.plan.executionStatus??"")} onSelect={index=>{autoReply.cancel();void assistant.selectReview(index);}}/><PlanReview key={conversation.plan.planId} plan={conversation.plan} conversational clarifying={busy||Boolean(conversation.draft.trim())} onBusyChange={setExecuting} onExecution={assistant.executed} onOpen={()=>setOpen(false)}/></>:null}
        </div>)}</div>}
        {conversation.retainedPreview?.actions[0]?<section aria-label="Предыдущий предпросмотр"><p role="status">Последний подготовленный вариант. Новые изменения ещё не выполнены.</p><DashboardCreationPreview action={conversation.retainedPreview.actions[0]}/></section>:null}
        {conversation.orchestration?<AssistantPlanProgress plan={conversation.orchestration} busy={busy} onControl={(action,step)=>void assistant.control(action,step)}/>:null}
        {assistant.error?<p className="error-message" role="alert">{assistant.error}</p>:null}
        {conversation.saved||assistant.error?<button type="button" className="ghost-button" disabled={busy||executing} onClick={()=>void assistant.reload()}>{conversation.saved?"Обновить разговор":"Повторить загрузку истории"}</button>:null}
        {conversation.pendingStatus==="PENDING"?<p role="status">Сообщение сохранено. Ожидаем завершения запроса…</p>:null}
        {conversation.resourceStatus==="UNAVAILABLE"||conversation.resourceStatus==="EXPIRED"?<p role="status">Связанная задача недоступна или срок её выполнения истёк. История сохранена.</p>:null}
        {!assistant.draftSaved&&conversation.draft?<p role="status">Черновик доступен только в этом окне: локальное сохранение недоступно.</p>:null}
      </div>
      {busy?<AssistantTraceProgress conversationId={conversation.id} turnId={assistant.activeTurnId} phase={assistant.phase}/>:null}
      {autoReply.visible&&defaultReply?<div className="assistant-auto-reply"><span>Если не начнёте отвечать, через <strong>{autoReply.remaining} с</strong> выберу: {defaultReply.label}.</span><button type="button" onClick={autoReply.cancel}>Отвечу сам</button></div>:null}
      {menu==="context"?<div className="assistant-context-picker"><strong>Контекст нового разговора</strong>{conversation.turns.length?<p>Чтобы изменить устройство, начните новый разговор.</p>:<><button type="button" onClick={()=>{const page=buildAssistantContext(location.pathname,location.search);assistant.patch({context:{...page.searchParams,...(page.deviceId?{deviceId:page.deviceId}:{})}});setMenu(null);}}>Добавить контекст текущей страницы</button>{deviceError?<p role="alert">{deviceError}</p>:null}{devices.map(device=><button type="button" key={device.id} onClick={()=>{assistant.patch({context:{deviceId:device.id}});setMenu(null);}}>{device.name}</button>)}</>}</div>:null}
      <form className="assistant-window-compose" data-navigation-persistent="true" onSubmit={event=>{event.preventDefault();void assistant.send();}}>
        {typeof conversation.context.deviceId==="string"?<span className="assistant-context-chip">{devices.find(device=>device.id===conversation.context.deviceId)?.name ?? "Выбрано устройство"}{!conversation.turns.length?<button type="button" aria-label="Убрать устройство из контекста" onClick={()=>assistant.patch({context:{}})}><X size={12}/></button>:null}</span>:null}
        <textarea ref={input} aria-label="Сообщение помощнику" value={conversation.draft} onCompositionStart={autoReply.cancel} onChange={event=>{autoReply.cancel();assistant.patch({draft:event.target.value});}} maxLength={2000} rows={2} placeholder={conversation.plan?"Что уточнить или изменить в макете?":"Спросите или опишите задачу…"} disabled={locked}/>
        <div className="assistant-compose-actions"><button type="button" className="ghost-button" disabled={locked||conversation.turns.length>0} onClick={()=>setMenu(menu==="context"?null:"context")}><Plus size={16}/>Добавить контекст</button><button type="submit" className="primary-button" aria-label="Отправить сообщение" disabled={locked||!conversation.draft.trim()}><ArrowUp size={17}/></button></div>
      </form>
    </section>
  </>;
}
