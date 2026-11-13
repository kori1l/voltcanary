import {useEffect,useRef,useState} from "react";
import {readDeviceSetup} from "./deviceSetupApi";

export function DeviceSetupResult({deviceId,initialStatus}:{deviceId:string;initialStatus:string}){
  const [status,setStatus]=useState(initialStatus),[busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null);
  const request=useRef<AbortController|null>(null);
  useEffect(()=>()=>request.current?.abort(),[]);
  async function refresh(){
    request.current?.abort();const controller=new AbortController();request.current=controller;setBusy(true);setError(null);
    try{const result=await readDeviceSetup(deviceId,controller.signal);if(!controller.signal.aborted)setStatus(result.setupStatus);}
    catch{if(!controller.signal.aborted)setError("Не удалось проверить источник. Последний подтверждённый результат сохранён.");}
    finally{if(!controller.signal.aborted)setBusy(false);}
  }
  return <div>
    <p>{status==="TELEMETRY_READY"?"Устройство и источник подключены. Получена свежая реальная телеметрия.":"Устройство и источник подключены. Ожидаем свежие данные; передача телеметрии пока не подтверждена."}</p>
    <button type="button" disabled={busy} onClick={()=>void refresh()}>Проверить поступление данных</button>
    {error?<p role="alert">{error}</p>:null}
  </div>;
}
