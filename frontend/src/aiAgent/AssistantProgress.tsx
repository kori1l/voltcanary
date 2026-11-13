import {LoaderCircle} from "lucide-react";
import {useEffect, useState} from "react";

export type AssistantPhase = "planning" | "reading" | "recovering";

export function AssistantProgress({phase,observedText}: {phase: AssistantPhase;observedText?:string}) {
  const [started] = useState(Date.now);
  const [elapsed, setElapsed] = useState(0);
  useEffect(() => {
    const timer = window.setInterval(() => setElapsed(Math.floor((Date.now() - started) / 1000)), 1000);
    return () => window.clearInterval(timer);
  }, [started]);
  const label = observedText ?? (phase === "planning" ? "Подготавливаю запрос…" : phase === "reading" ? "Читаю данные…" : "Восстанавливаю задачу…");
  return <div className="assistant-progress">
    <div role="status"><LoaderCircle size={15} aria-hidden="true"/><span>{label}</span></div>
    <span role="timer" aria-live="off" aria-label="Время ожидания">{Math.floor(elapsed / 60)}:{String(elapsed % 60).padStart(2, "0")}</span>
    <small>Можно свернуть окно — работа продолжится.</small>
  </div>;
}
