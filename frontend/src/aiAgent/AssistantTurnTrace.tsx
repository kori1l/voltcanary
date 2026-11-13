import {useEffect,useRef,useState} from "react";
import {sessionIdentityRevision} from "../api/client";
import {exportAssistantTrace,getAssistantTrace,type AssistantTraceEvent} from "./assistantTraceApi";
import {AssistantProgress,type AssistantPhase} from "./AssistantProgress";

const stages:Record<string,string>={TURN:"Подготовка ответа",CONTEXT:"Выбор контекста",CONTEXT_READ:"Чтение контекста",CAPABILITIES:"Доступные данные и виджеты",MODEL_CALL:"Вызов модели",PROVIDER_RESPONSE:"Ответ провайдера получен",PARSING:"Проверка формата ответа",CANDIDATE:"Предложенный вариант",SANITIZATION:"Проверка полей",COMPILATION:"Подготовка дашборда",DEVICE_COMPILATION:"Подготовка устройства",VALIDATION:"Проверка плана",REPAIR:"Исправление варианта",REVIEW:"Сохранение review",MODEL_RUN:"Запись вызова",EXECUTION:"Выполнение плана",TOOL_EXECUTION:"Выполнение действия",RECONCILIATION:"Проверка результата",CAPTURE:"Полнота диагностики"};
const statuses:Record<string,string>={STARTED:"Начат",SUCCEEDED:"Завершён",FAILED:"Ошибка",UNKNOWN:"Результат неизвестен",RECEIVED:"Получен",OBSERVED:"Записан"};
export function AssistantTraceProgress({conversationId,turnId,phase}:{conversationId:string;turnId:string|null;phase:AssistantPhase}) {
  const [observed,setObserved]=useState<string>();
  useEffect(()=>{
    let active=true;const actor=sessionIdentityRevision();setObserved(undefined);
    if(!turnId)return;
    const load=async()=>{try{const events=await getAssistantTrace(conversationId,turnId),last=events[events.length-1];if(active&&actor===sessionIdentityRevision()&&last)setObserved(`${stages[last.stage]??last.stage}: ${statuses[last.status]??last.status}`);}catch{/* Missing diagnostics never imply a completed stage. */}};
    void load();const timer=window.setInterval(()=>void load(),2500);return()=>{active=false;window.clearInterval(timer);};
  },[conversationId,turnId]);
  return <AssistantProgress phase={phase} observedText={observed}/>;
}
export function AssistantTurnTrace({conversationId,turnId,pending=false}:{conversationId:string;turnId:string;pending?:boolean}) {
  const [open,setOpen]=useState(false),[events,setEvents]=useState<AssistantTraceEvent[]>([]),[error,setError]=useState<string|null>(null),[exporting,setExporting]=useState(false);
  const generation=useRef(0);
  useEffect(()=>{
    const version=++generation.current,actor=sessionIdentityRevision();let disposed=false;
    if(!open){setEvents([]);return;}
    const active=()=>!disposed&&version===generation.current&&actor===sessionIdentityRevision();
    const load=async()=>{try{const next=await getAssistantTrace(conversationId,turnId);if(active()){setEvents(next);setError(null);}}catch(failure){if(active())setError(failure instanceof Error?failure.message:"Диагностика недоступна.");}};
    void load();const timer=pending?window.setInterval(()=>void load(),2500):undefined;
    return()=>{disposed=true;if(timer)window.clearInterval(timer);};
  },[conversationId,turnId,open,pending]);
  async function download(){const actor=sessionIdentityRevision();setExporting(true);try{await exportAssistantTrace(conversationId,turnId);}catch(failure){if(actor===sessionIdentityRevision())setError(failure instanceof Error?failure.message:"Экспорт недоступен.");}finally{if(actor===sessionIdentityRevision())setExporting(false);}}
  return <details open={open} onToggle={event=>setOpen(event.currentTarget.open)} className="assistant-turn-trace"><summary>Этапы запроса</summary>
    {error?<p role="alert">{error}</p>:null}
    {open?<><ol aria-label="Записанные этапы запроса">{events.map(event=><li key={event.id}><span>{stages[event.stage]??event.stage}: {statuses[event.status]??event.status}</span>{event.durationMs!=null?<small> · {event.durationMs} мс</small>:null}{event.code?<code> {event.code}</code>:null}{event.truncated?<small> · Подробности сокращены</small>:null}<details><summary>Технические данные</summary><pre>{JSON.stringify({sequence:event.sequence,attemptId:event.attemptId,reviewId:event.reviewId,actionId:event.actionId,modelRunId:event.modelRunId,...event.details},null,2)}</pre></details></li>)}</ol>
    {!events.length&&!error?<p>Записанных этапов пока нет. Старые разговоры не содержат новую диагностику.</p>:null}
    <button type="button" disabled={exporting||!events.length} onClick={()=>void download()}>Экспорт диагностики</button></>:null}
  </details>;
}
