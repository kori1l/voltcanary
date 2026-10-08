import { useEffect, useRef, useState } from "react";
import { getAccessToken } from "../api/client";

export function ReadRefresh({ scope, refresh }: { scope: string; refresh: () => Promise<unknown> }) {
  const [interval, setIntervalMs] = useState(0);
  const callback = useRef(refresh);
  callback.current = refresh;
  useEffect(() => {
    if (!interval) return;
    let cancelled = false;
    let failures = 0;
    let running = false;
    let timer: number | undefined;
    const schedule = () => {
      window.clearTimeout(timer);
      if (!cancelled && !document.hidden) timer = window.setTimeout(tick, Math.min(interval * 2 ** failures, 300_000) * (1 + Math.random() * 0.1));
    };
    const tick = async () => {
      if (cancelled || document.hidden || running) return;
      if (!getAccessToken()) { schedule(); return; }
      running = true;
      try { const result = await callback.current(); failures = result === null || result === false ? Math.min(failures + 1, 4) : 0; }
      catch { failures = Math.min(failures + 1, 4); }
      finally { running = false; schedule(); }
    };
    const visibility = () => { window.clearTimeout(timer); if (!running) schedule(); };
    document.addEventListener("visibilitychange", visibility);
    schedule();
    return () => { cancelled = true; window.clearTimeout(timer); document.removeEventListener("visibilitychange", visibility); };
  }, [interval, scope]);
  return <label>Read refresh <select aria-label="Read refresh" value={interval} onChange={(event) => setIntervalMs(Number(event.target.value))}>
    <option value={0}>Off</option><option value={30_000}>30 seconds</option><option value={60_000}>1 minute</option><option value={300_000}>5 minutes</option>
  </select></label>;
}
