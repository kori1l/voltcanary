import {useEffect,useRef} from "react";
import {getAccessToken} from "../api/client";
export function useReadRefresh({scope,refresh,interval}:{scope:string;refresh:()=>Promise<unknown>;interval:number}){
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
}
