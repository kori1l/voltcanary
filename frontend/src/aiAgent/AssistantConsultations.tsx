import {useEffect, useState} from "react";
import {cancelAssistantConsultation, listAssistantConsultations, type AssistantConsultation} from "./conversationApi";

const labels: Record<AssistantConsultation["status"], string> = {
  QUEUED: "В очереди",
  RUNNING: "Выполняется",
  WAITING_DATA: "Ожидает данных",
  WAITING_CHILD: "Ожидает результатов дочерней задачи",
  WAITING_USER: "Ожидает ответа",
  SUCCEEDED: "Завершена",
  FAILED: "Ошибка",
  CANCELLED: "Отменена",
  STALE: "Данные изменились",
  EXPIRED: "Время ожидания истекло",
};
const cancellable = new Set(["QUEUED", "RUNNING", "WAITING_DATA", "WAITING_CHILD", "WAITING_USER"]);

export function AssistantConsultations({planId, revision}: {planId: string; revision: number}) {
  const [result, setResult] = useState<{revision: number; items: AssistantConsultation[]; error?: boolean}>();
  const [pending, setPending] = useState(false);
  const [cancelError, setCancelError] = useState(false);
  const [retry, setRetry] = useState<{id: string; revision: number; key: string}>();

  useEffect(() => {
    let active = true;
    void listAssistantConsultations(planId).then(items => {
      if (active) setResult({revision, items});
    }).catch(() => {
      if (active) setResult({revision, items: [], error: true});
    });
    return () => { active = false; };
  }, [planId, revision]);

  async function cancel(task: AssistantConsultation) {
    const key = retry?.id === task.id && retry.revision === task.revision ? retry.key : crypto.randomUUID();
    setRetry({id: task.id, revision: task.revision, key});
    setPending(true);
    setCancelError(false);
    try {
      await cancelAssistantConsultation(planId, task, key);
      const items = await listAssistantConsultations(planId);
      setResult(current => current?.revision === revision ? {revision, items} : current);
      setRetry(undefined);
    } catch {
      setCancelError(true);
      try {
        const items = await listAssistantConsultations(planId);
        setResult(current => current?.revision === revision ? {revision, items} : current);
      } catch {
        // Preserve the last known status and request key for a retry.
      }
    } finally {
      setPending(false);
    }
  }

  if (result?.revision !== revision) return null;
  if (result.error) return <small role="status">Статус консультаций недоступен.</small>;
  if (!result.items.length) return null;
  return <section aria-label="Консультации" className="assistant-plan-progress">
    <strong>Консультации</strong>
    <ul>{result.items.map(task => <li key={task.id}>
      {labels[task.status]}
      {cancellable.has(task.status) && <button type="button" disabled={pending} onClick={() => void cancel(task)}>Отменить консультацию</button>}
    </li>)}</ul>
    {cancelError && <small role="status">Ответ на запрос отмены недоступен. Проверьте текущий статус.</small>}
  </section>;
}
