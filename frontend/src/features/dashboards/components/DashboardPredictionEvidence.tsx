import {Link} from "react-router-dom";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";

type Row=Record<string,unknown>;
const percent=(value:unknown)=>typeof value==="number" && Number.isFinite(value) && value>=0 && value<=1?`${(value*100).toLocaleString(undefined,{maximumFractionDigits:2})}%`:"Unavailable";
function risk(row:Row){return row.modelFamily==="FAILURE_PREDICTION" && row.predictionStatus==="TRAINED_MODEL" && percent(row.failureRiskScore)!=="Unavailable"?row.failureRiskScore as number:null;}
const states:Record<string,string>={TRAINED_MODEL:"Trained model",BASELINE_HEURISTIC:"Baseline heuristic",READY_HEURISTIC:"Baseline heuristic",UNAVAILABLE:"Unavailable",INSUFFICIENT_DATA:"Insufficient data"};
function modelHref(row:Row){
  if(row.predictionStatus!=="TRAINED_MODEL" || typeof row.modelVersionId!=="string" || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(row.modelVersionId))return null;
  const query=new URLSearchParams({tab:"models",modelId:row.modelVersionId});
  if(["FAILURE_PREDICTION","ANOMALY_DETECTION"].includes(String(row.modelFamily)))query.set("family",String(row.modelFamily));
  return `/settings/ml?${query}`;
}

export function DashboardPredictionEvidence({widget,data}:{widget:DashboardWidget;data:Row}){
  const items=Array.isArray(data.rows)?data.rows.filter((r):r is Row=>!!r && typeof r==="object"):[];
  const names=new Map((Array.isArray(data.devices)?data.devices as Row[]:[]).map(d=>[d.id,d.name]));
  const ordered=widget.presentation.predictionSort==="RISK_DESC"?[...items].sort((a,b)=>(risk(b) ?? -1)-(risk(a) ?? -1)):items;
  const showModel=widget.presentation.showPredictionModel===true,showTime=widget.presentation.showPredictionTime===true;
  return <><p className="dashboard-prediction-caption">Stored predictions · confidence is not measured accuracy.{data.hasNext?` Showing ${items.length} of ${String(data.total)}; sorting applies to this page.`:""}</p>
    {ordered.length?<div className="dashboard-table-scroll" role="region" aria-label="Stored predictions" tabIndex={0}><table className="dashboard-prediction-table"><thead><tr><th>Equipment</th><th>Failure risk</th><th>Horizon</th><th>Confidence</th><th>Prediction state</th>{showModel?<th>Model</th>:null}{showTime?<th>Calculated</th>:null}</tr></thead><tbody>{ordered.map((row,index)=>{
      const score=risk(row),id=String(row.deviceId ?? ""),name=names.get(id),href=modelHref(row);
      return <tr key={id+index}><td>{name?<Link to={`/devices/${encodeURIComponent(id)}`}>{String(name)}</Link>:"Unavailable equipment"}</td><td><span className="dashboard-prediction-risk">{score===null?"Unavailable":percent(score)}</span>{score!==null?<meter min={0} max={1} low={0.25} high={0.6} optimum={0} value={score} aria-label={`Failure risk for ${String(name ?? "equipment")}`}/>:null}</td><td>{typeof row.horizonDays==="number" && row.horizonDays>0?`${row.horizonDays} days`:"Unavailable"}</td><td>{["TRAINED_MODEL","BASELINE_HEURISTIC","READY_ML","READY_HEURISTIC"].includes(String(row.predictionStatus))?percent(row.confidence):"Unavailable"}</td><td><>{href?<Link className="dashboard-state-badge dashboard-model-link" to={href} title={`Open ${String(row.modelName ?? "recorded model")} v${String(row.modelVersion ?? "")}`}>Trained model</Link>:<span className="dashboard-state-badge">{states[String(row.predictionStatus)] ?? String(row.predictionStatus ?? "Unavailable")}</span>}</>{row.fallbackReason?<small>{String(row.fallbackReason)}</small>:null}</td>{showModel?<td>{row.modelName?`${String(row.modelName)} · ${String(row.modelVersion ?? "Unavailable")}`:"Unavailable"}</td>:null}{showTime?<td>{typeof row.measuredAt==="string" && Number.isFinite(Date.parse(row.measuredAt))?<time dateTime={row.measuredAt}>{new Date(row.measuredAt).toLocaleString()}</time>:"Unavailable"}</td>:null}</tr>;
    })}</tbody></table></div>:<p>No stored predictions for this selection. Open ML workspace to review deployment and calculation.</p>}
  </>;
}
