import {VehicleFieldChart} from "./VehicleFieldChart";
import type {DashboardWidget} from "../dashboards/dashboardsApi";
import type {PositionEvidence,PositionPoint} from "./positionApi";
import "./vehicle.css";

type Observed={value:string|number|boolean;unit:string;label:string};
export function observedVehicleField(point:PositionPoint,key:string):Observed|null {
  const field=point.fields?.[key];
  if(field && (typeof field.value!=="number" || Number.isFinite(field.value)))return field;
  const value=key.startsWith("transport.")?point.transport?.[key.slice(10) as keyof NonNullable<PositionPoint["transport"]>]:point[key as keyof PositionPoint];
  if(typeof value!=="string" && typeof value!=="boolean" && !(typeof value==="number" && Number.isFinite(value)))return null;
  return {value,unit:key==="speedKmh"?"km/h":"",label:key};
}
export function VehicleFieldWidget({widget,evidence}:{widget:DashboardWidget;evidence:PositionEvidence|null}) {
  const key=String(widget.presentation.vehicleField ?? ""),latest=evidence?.latest;
  const format=(field:Observed)=>typeof field.value==="boolean"
    ?String(field.value?widget.presentation.trueLabel ?? (key.endsWith("doorsOpen")?"Открыты":"Да"):widget.presentation.falseLabel ?? (key.endsWith("doorsOpen")?"Закрыты":"Нет"))
    :typeof field.value==="number"?field.value.toLocaleString(undefined,{maximumFractionDigits:1}):field.value;
  if(!key)return <p role="status">Select a vehicle field in panel settings.</p>;
  if(widget.type==="vehicle-timeline") {
    const samples=(evidence?.history.points ?? []).map(point=>({point,field:observedVehicleField(point,key)}))
      .filter((sample):sample is {point:PositionPoint;field:Observed}=>!!sample.field)
      .sort((a,b)=>Date.parse(b.point.recordedAt)-Date.parse(a.point.recordedAt));
    if(widget.presentation.visualization==="line")return <VehicleFieldChart title={widget.title} from={evidence?.history.from} to={evidence?.history.to}
      samples={(evidence?.history.points ?? []).map(point=>{const field=observedVehicleField(point,key);return {at:point.recordedAt,value:field?.value ?? null};})}
      falseLabel={String(widget.presentation.falseLabel ?? (key.endsWith("doorsOpen")?"Закрыты":"Нет"))}
      trueLabel={String(widget.presentation.trueLabel ?? (key.endsWith("doorsOpen")?"Открыты":"Да"))} truncated={evidence?.history.truncated===true}/>;
    return <div className="vehicle-field-history">
      {!samples.length?<p role="status">Нет записанных состояний за выбранный период.</p>:<ol aria-label={widget.title}>{samples.map(({point,field},index)=><li key={`${point.recordedAt}:${index}`}>
        <time dateTime={point.recordedAt}>{new Date(point.recordedAt).toLocaleString()}</time><strong className={typeof field.value==="boolean"?field.value?"state-on":"state-off":undefined}>{format(field)} {field.unit}</strong>
      </li>)}</ol>}
      <small>Записанные наблюдения. Между измерениями состояние неизвестно.</small>
      {evidence?.history.truncated?<p role="status">Показаны последние 1000 наблюдений. Сократите период для полной истории.</p>:null}
    </div>;
  }
  const position=latest?.position,field=position?observedVehicleField(position,key):null;
  const stale=latest?.freshness!=="FRESH";
  if(widget.presentation.compact===true) return <div className="vehicle-field-compact">
    <strong className="dashboard-primary-value" title={field?`${format(field)} ${field.unit}`:undefined}>{field?format(field):"—"} {field?.unit?<span>{field.unit}</span>:null}</strong>
    {!field?<span role="status" className="vehicle-field-age">Нет данных</span>:stale?<span role="status" className="vehicle-field-age" title={position?`Последнее наблюдение: ${new Date(position.recordedAt).toLocaleString()}`:undefined}>Устарело</span>:null}
  </div>;
  return <div className="vehicle-field-value">
    {field&&!stale?<p className="dashboard-primary-value">{format(field)} <span>{field.unit}</span></p>:<p role="status">Текущее значение недоступно.</p>}
    {field&&stale?<p>Последнее наблюдение: <strong>{format(field)} {field.unit}</strong></p>:null}
    {widget.presentation.showLegend===true&&field?<small>{field.label}</small>:null}
    {position?<time dateTime={position.recordedAt}>{stale?"Устаревшие данные · ":"Измерено · "}{new Date(position.recordedAt).toLocaleString()}</time>:null}
  </div>;
}
