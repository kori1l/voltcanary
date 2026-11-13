import {useEffect,useState} from "react";
import {getTelemetryFields,type TelemetryField} from "../telemetry/telemetryFieldsApi";
import {dashboardCapabilities,type DashboardDataChoice} from "../features/dashboards/model/dashboardCapabilitiesApi";

export function VehicleFieldSelection({deviceId,sourceId,value,onChange,allowedTypes,onValidityChange,widgetType,visualization}:{deviceId:string;sourceId:string;value:string;onChange:(value:string)=>void;allowedTypes?:readonly string[];onValidityChange?:(valid:boolean)=>void;widgetType?:string;visualization?:string}) {
  const [fields,setFields]=useState<TelemetryField[]>([]),[error,setError]=useState("");
  const [choices,setChoices]=useState<DashboardDataChoice[]>([]),[loaded,setLoaded]=useState(false);
  useEffect(()=>{
    const controller=new AbortController();setFields([]);setChoices([]);setLoaded(false);setError("");
    if(deviceId&&sourceId&&widgetType)void dashboardCapabilities([deviceId],controller.signal)
      .then(result=>{if(!controller.signal.aborted){setChoices(result.choices.filter(choice=>choice.family==="RECORDED_VEHICLE_FIELD"&&choice.externalSourceId===sourceId&&choice.key!==null));setLoaded(true);}})
      .catch(()=>{if(!controller.signal.aborted)setError("Recorded field capabilities are unavailable.");});
    else if(deviceId && sourceId)void getTelemetryFields(deviceId,sourceId,controller.signal)
      .then(items=>{if(!controller.signal.aborted)setFields(items);})
      .catch(()=>{if(!controller.signal.aborted)setError("Recorded field choices are unavailable.");});
    return ()=>controller.abort();
  },[deviceId,sourceId,widgetType]);
  const compatible=(choice:DashboardDataChoice)=>choice.views.some(view=>view.widgetType===widgetType&&(!visualization||view.visualizations.includes(visualization)));
  const incompatible=widgetType?loaded&&!!value&&!choices.some(choice=>choice.key===value&&compatible(choice)):fields.some(field=>field.key===value && allowedTypes && !allowedTypes.includes(field.type));
  useEffect(()=>{onValidityChange?.(!incompatible&&!error&&(!widgetType||loaded));},[incompatible,error,widgetType,loaded,onValidityChange]);
  const available=widgetType?choices.map(choice=>({key:choice.key!,label:choice.label,type:choice.valueType,unit:choice.unit,disabled:!compatible(choice)})):fields.map(field=>({...field,disabled:!!allowedTypes&&!allowedTypes.includes(field.type)}));
  return <label>Vehicle field<select value={value} onChange={event=>onChange(event.target.value)}><option value="">Select a recorded field</option>
    {value&&!available.some(field=>field.key===value)?<option value={value}>{value} (no current value)</option>:null}
    {available.map(field=><option key={field.key} value={field.key} disabled={field.disabled}>{field.label} · {field.type}{field.unit?` · ${field.unit}`:""}</option>)}
  </select>{widgetType&&!loaded&&!error&&deviceId&&sourceId?<small role="status">Loading compatible recorded fields…</small>:null}{incompatible?<small role="alert">This presentation does not support the recorded field type. Select a compatible field or another visualization.</small>:null}{error?<small role="status">{error}</small>:null}</label>;
}
