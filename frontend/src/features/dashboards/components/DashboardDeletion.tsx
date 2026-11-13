import {useEffect,useRef,useState} from "react";
import {useNavigate} from "react-router-dom";
import {Dialog} from "../../../components/Dialog";
import {deleteDashboard,getDashboard} from "../../../dashboards/dashboardsApi";
import {sessionIdentityRevision} from "../../../api/client";
import {forgetDashboardLayout} from "../model/dashboardLayoutCache";

export function DashboardDeletion({id,name,disabled=false,onDeleted}:{id:string;name:string;disabled?:boolean;onDeleted?:()=>void}) {
  const navigate=useNavigate();
  const [open,setOpen]=useState(false),[etag,setEtag]=useState<string|null>(null);
  const [busy,setBusy]=useState(false),[error,setError]=useState<string|null>(null);
  const lock=useRef(false),generation=useRef(0);
  useEffect(()=>()=>{generation.current++;},[id]);

  async function prepare() {
    if(lock.current || disabled)return;
    lock.current=true;setOpen(true);setBusy(true);setError(null);setEtag(null);
    const identity=sessionIdentityRevision(),request=++generation.current;
    try {
      const view=await getDashboard(id);
      if(request!==generation.current || identity!==sessionIdentityRevision())return;
      if(!view.permissions.includes("delete"))throw new Error("Удаление доступно только владельцу дашборда.");
      setEtag(view.draftEtag);
    } catch(error) {
      if(request===generation.current && identity===sessionIdentityRevision())setError(error instanceof Error?error.message:"Не удалось подготовить удаление.");
    } finally {lock.current=false;setBusy(false);}
  }

  async function remove() {
    if(lock.current || !etag || disabled)return;
    lock.current=true;setBusy(true);setError(null);
    const identity=sessionIdentityRevision(),request=generation.current;
    try {
      await deleteDashboard(id,etag);
      if(request!==generation.current || identity!==sessionIdentityRevision())return;
      forgetDashboardLayout(id);setOpen(false);onDeleted?.();navigate("/dashboards");
    } catch(error) {
      if(request===generation.current && identity===sessionIdentityRevision()) {
        setError(error instanceof Error?error.message:"Не удалось удалить дашборд.");
        setEtag(null);
      }
    } finally {lock.current=false;setBusy(false);}
  }

  return <section className="dashboard-settings-section"><h3>Удаление</h3>
    <button type="button" className="danger-button" disabled={disabled || busy} onClick={()=>void prepare()}>Удалить дашборд</button>
    {open?<Dialog title="Удалить дашборд?" busy={busy} onClose={()=>{generation.current++;setOpen(false);setEtag(null);}}>
      <p>Дашборд «{name}» исчезнет из списка и станет недоступен всем пользователям. Восстановить его через интерфейс нельзя. Устройства и телеметрия сохранятся.</p>
      {error?<p role="alert">{error}</p>:null}
      {busy && !etag?<p role="status">Подготовка удаления…</p>:null}
      <div className="action-row"><button type="button" className="danger-button" disabled={disabled || busy || !etag} onClick={()=>void remove()}>Подтвердить удаление</button>
        {error?<button type="button" className="ghost-button" disabled={busy || disabled} onClick={()=>void prepare()}>Обновить перед повтором</button>:null}
        <button type="button" className="ghost-button" disabled={busy} onClick={()=>{generation.current++;setOpen(false);setEtag(null);}}>Отмена</button></div>
    </Dialog>:null}
  </section>;
}
