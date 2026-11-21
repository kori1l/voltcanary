import type {OrchestrationProgress} from "./conversationApi";

const labels:Record<string,string>={READY:"Готов к подготовке",BLOCKED:"Ожидает предыдущего шага",PREPARING:"Подготовка",WAITING_INPUT:"Нужен ответ",WAITING_CONFIRMATION:"Ожидает подтверждения",WAITING_DATA:"Ожидает данных",EXECUTING:"Выполняется",SUCCEEDED:"Выполнен",FAILED:"Не выполнен",UNKNOWN:"Результат уточняется",CANCELLED:"Отменён",SUPERSEDED:"Заменён новым шагом"};
const intents:Record<string,string>={CREATE_DEVICE:"Создание устройства",CREATE_DASHBOARD:"Подготовка дашборда",UPDATE_DASHBOARD_DRAFT:"Изменение черновика дашборда",CREATE_DATA_SOURCE:"Подключение источника",CREATE_SIMULATOR_CONFIG:"Настройка симулятора",VALIDATE_SIMULATOR_YAML:"Проверка конфигурации",CREATE_ALERT_RULE:"Создание правила",UPDATE_ALERT_RULE:"Изменение правила",TEST_ALERT_RULE:"Проверка правила",EXPLAIN_ALERT:"Объяснение оповещения",OPEN_TELEMETRY_WINDOW:"Просмотр телеметрии",CREATE_DEMO_DEVICE:"Создание демо-устройства",START_SIMULATION:"Запуск симуляции",STOP_SIMULATION:"Остановка симуляции"};
export function AssistantPlanProgress({plan,busy,onControl,onContinue}:{plan:OrchestrationProgress;busy:boolean;onControl:(action:"CANCEL"|"RETRY"|"RESUME",stepId?:string|null)=>void;onContinue?:(stepId:string)=>void}){
  const terminal=["SUCCEEDED","CANCELLED","EXPIRED"].includes(plan.status);
  return <section aria-label="План действий" className="assistant-plan-progress">
    <strong>План действий</strong>
    <ol>{plan.steps.map(step=><li key={step.stepId}>
      <span>{intents[step.intentId]??"Действие"}: {labels[step.status]??step.status}</span>
      {step.diagnostic?<p role="status">{step.diagnostic.message}</p>:null}
      {onContinue&&plan.status==="ACTIVE"&&step.status==="READY"?<button type="button" disabled={busy} onClick={()=>onContinue(step.stepId)}>Продолжить подготовку</button>:null}
      {!terminal&&step.status==="FAILED"?<button type="button" disabled={busy} onClick={()=>onControl("RETRY",step.stepId)}>Разрешить повторную подготовку</button>:null}
      {step.status==="UNKNOWN"&&plan.status!=="EXPIRED"?<button type="button" disabled={busy} onClick={()=>onControl("RETRY",step.stepId)}>Проверить результат выполнения</button>:null}
    </li>)}</ol>
    {!terminal?<button type="button" onClick={()=>onControl("CANCEL")}>Отменить план</button>:null}
    {plan.status==="CANCELLED"?<p>Выполненные действия сохранены. Для новой работы начните новый план.</p>:null}
  </section>;
}
