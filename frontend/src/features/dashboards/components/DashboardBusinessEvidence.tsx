import {TelemetryChart} from "../../telemetry/components/TelemetryChart";
import {DashboardEnergyEvidence} from "./DashboardEnergyEvidence";
import type {TelemetryEnvelopeBucket} from "../../../telemetry/telemetryApi";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import type {WidgetQueryPanel} from "../../../dashboards/dashboardQueriesApi";

type Row=Record<string,unknown>;
const record=(value:unknown):Row=>value && typeof value==="object" && !Array.isArray(value)?value as Row:{};
const rows=(value:unknown):Row[]=>Array.isArray(value)?value.filter((item)=>item && typeof item==="object") as Row[]:[];
function text(value:unknown){return value===null || value===undefined?"Unavailable":typeof value==="number"?value.toLocaleString():typeof value==="boolean"?(value?"Yes":"No"):String(value);}
const labels:Record<string,string>={deviceId:"Equipment",title:"Title",name:"Name",message:"Description",severity:"Severity",status:"Status",level:"Recorded risk",score:"Risk score",confidence:"Confidence (not accuracy)",predictionStatus:"Prediction state",modelFamily:"Model family",horizonDays:"Horizon (days)",createdAt:"Created",measuredAt:"Measured",startAt:"Starts",endAt:"Ends",explanation:"Explanation",suppressAlerts:"Suppress alerts",excludeFromAnalytics:"Exclude from analytics",excludeFromMl:"Exclude from ML"};
function BusinessRows({source,devices}:{source:Row;devices:Row[]}){
  const data=rows(source.rows);const fields=Object.keys(labels).filter((key)=>data.some((row)=>Object.prototype.hasOwnProperty.call(row,key)));const names=new Map(devices.map((device)=>[device.id,device.name]));
  return <><p>{text(source.total ?? data.length)} recorded entries{source.hasNext?` · Showing ${data.length}; open the full working view for more`:""}</p>{data.length?<div className="dashboard-table-scroll" role="region" aria-label="Recorded working entries" tabIndex={0}><table><thead><tr>{fields.map((field)=><th key={field}>{labels[field]}</th>)}</tr></thead><tbody>{data.map((row,index)=><tr key={index}>{fields.map((field)=><td key={field}>{field==="deviceId"?text(names.get(row[field]) ?? "Unavailable equipment"):text(row[field])}</td>)}</tr>)}</tbody></table></div>:<p>No recorded entries for this selection.</p>}</>;
}
function SeriesEvidence({data,panel,widget}:{data:Row;panel:WidgetQueryPanel;widget:DashboardWidget}){
  const devices=rows(data.devices);const names=new Map(devices.map((device)=>[device.id,device.name]));
  return <>{rows(data.series).map((series,index)=>{
    const bins=rows(series.buckets).filter((bucket)=>["minimum","maximum","first_value","last_value","sampleCount","gapCount"].every((key)=>typeof bucket[key]==="number" && Number.isFinite(bucket[key])) && Number(bucket.sampleCount)>0 && ["firstAt","lastAt","minimumAt","maximumAt"].every((key)=>typeof bucket[key]==="string" && Number.isFinite(Date.parse(String(bucket[key])))));
    const buckets:TelemetryEnvelopeBucket[]=bins.map((bucket)=>({from:String(bucket.firstAt),to:String(bucket.lastAt),count:Number(bucket.sampleCount),min:Number(bucket.minimum),max:Number(bucket.maximum),minAt:String(bucket.minimumAt),maxAt:String(bucket.maximumAt),firstAt:String(bucket.firstAt),firstValue:Number(bucket.first_value),lastAt:String(bucket.lastAt),lastValue:Number(bucket.last_value),gapCount:Number(bucket.gapCount ?? 0)}));
    const unit=String(bins[0]?.unit ?? "");const label=`${names.get(series.deviceId) ?? "Recorded equipment"} · ${series.metric ?? "Measurement"}`;
    if(!panel.time || !buckets.length)return <p key={index}>{label}: no recorded samples in this interval.</p>;
    return <div className="dashboard-series" key={index}>{widget.presentation?.showLegend===true?<strong>{label} · {unit}</strong>:null}<TelemetryChart fillContainer showSummary={widget.presentation?.showCalculation===true} showAxes={widget.presentation?.showAxes!==false} series={[]} label={label} unit={unit} range={{from:panel.time.from,to:panel.time.to,preset:"custom"}} events={[]} showPoints maxDisplayPoints={400} evidence={{deviceId:String(series.deviceId),metric:String(series.metric),points:[],unit,buckets,rawPointCount:buckets.reduce((sum,bucket)=>sum+bucket.count,0)}}/>{widget.presentation?.showCalculation===true?<small>Observed minimum/maximum ranges. Blank intervals and gaps are not interpolated.</small>:null}</div>;
  })}</>;
}
export function DashboardBusinessEvidence({widget,panel}:{widget:DashboardWidget;panel:WidgetQueryPanel}){
  const data=record(panel.data),devices=rows(data.devices),sources=record(data.sources);
  if(widget.type==="context-note")return <p className="dashboard-context-note">{text(data.text)}</p>;
  if(widget.type==="energy-period" || widget.type==="peak-and-ranking")return <DashboardEnergyEvidence widget={widget} data={data}/>;
  if(widget.type==="fleet-count" || widget.type==="quality-and-freshness"){
    const counts=record(data.counts);return <><dl className="dashboard-kpis">{[["registered","Registered equipment"],["fresh","Fresh measurements"],["stale","Stale measurements"],["unknown","Measurement age unknown"]].filter(([key])=>Object.prototype.hasOwnProperty.call(counts,key)).map(([key,label])=><div key={key}><dt>{label}</dt><dd>{text(counts[key])}</dd></div>)}</dl>{widget.presentation?.showCalculation===true?<small>Registration and measurement age are separate facts.</small>:null}</>;
  }
  if(widget.type==="latest-value"){
    const value=record(data.value);return <><p className="dashboard-primary-value">{text(value.value)} <span>{text(value.unit)}</span></p>{widget.presentation?.showLegend===true?<p>{text(namesForDevice(devices,value.deviceId))} · {text(value.metric)}</p>:null}{widget.presentation?.showCalculation===true?<small>Measured {text(value.measuredAt)} · Quality {text(value.quality)}</small>:null}</>;
  }
  if(widget.type==="metric-summary"){
    const summary=record(data.summary),field=String(data.reducer ?? "mean");return <><p className="dashboard-primary-value">{text(summary[field])} <span>{text(summary.unit)}</span></p>{widget.presentation?.showCalculation===true?<p>{field} across {text(summary.sampleCount)} accepted samples · {text(summary.deviceCount)} equipment with evidence</p>:null}{widget.presentation?.showComparison===true && data.comparison?<p>Previous-period change: {text(record(data.comparison).difference)} {text(summary.unit)}; percent {record(data.comparison).percent===null?"unavailable":`${text(record(data.comparison).percent)}%`}</p>:null}</>;
  }
  if(widget.type==="time-series")return <SeriesEvidence data={data} panel={panel} widget={widget}/>;
  if(widget.type==="incident-list")return <BusinessRows source={record(sources.incidents)} devices={devices}/>;
  if(widget.type==="maintenance-agenda")return <BusinessRows source={record(sources.maintenance)} devices={devices}/>;
  if(widget.type==="risk-list" || widget.type==="prediction-evidence")return <BusinessRows source={data} devices={devices}/>;
  return <p>Recorded evidence for {panel.evaluatedCount} of {panel.requestedCount} authorized equipment. Open details for the full measurement fields.</p>;
}
function namesForDevice(devices:Row[],id:unknown){return devices.find((device)=>device.id===id)?.name ?? "Recorded equipment";}

