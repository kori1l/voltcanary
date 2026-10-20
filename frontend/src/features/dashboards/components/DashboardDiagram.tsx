import {useState} from "react";
import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import type {WidgetQueryPanel} from "../../../dashboards/dashboardQueriesApi";
import {diagramEvidence, type Visualization} from "../model/dashboardVisualization";

const colors=["#3b82f6","#f59e0b","#94a3b8","#8b5cf6","#14b8a6"];
/** SVG follows the existing telemetry renderer; no separate chart dependency or query. */
export function DashboardDiagram({widget,panel,kind}:{widget:DashboardWidget;panel:WidgetQueryPanel;kind:Visualization}) {
  const evidence=diagramEvidence(widget,panel);
  const [focused,setFocused]=useState<number|null>(null);
  if (evidence.reason) return <span className="dashboard-state-badge" role="status">{evidence.reason}</span>;
  const {values,total}=evidence, circular=kind==="pie" || kind==="donut";
  const min=Math.min(0,...values.map(item=>item.value)),max=Math.max(1,...values.map(item=>item.value));
  const y=(value:number)=>90-(value-min)/(max-min)*80;
  const x=(index:number)=>values[index].at && panel.time?10+(Date.parse(values[index].at!)-Date.parse(panel.time.from))/Math.max(1,Date.parse(panel.time.to)-Date.parse(panel.time.from))*180:10+index/Math.max(1,values.length-1)*180;
  const intervals=values.slice(1).flatMap((v,i)=>v.at && values[i].at?[Date.parse(v.at)-Date.parse(values[i].at!)]:[]).filter(v=>v>0).sort((a,b)=>a-b);
  const device=Array.isArray(panel.data?.devices)?(panel.data.devices as Record<string,unknown>[])[0]:undefined;
  const cadence=typeof device?.cadenceSeconds==="number"?device.cadenceSeconds*1000:intervals[Math.floor(intervals.length/2)] ?? 0;
  const gapThreshold=Math.max(900000,cadence*3);
  const recordedGaps=values.some(v=>v.gap) || intervals.some(v=>v>gapThreshold);
  const disconnected=(index:number)=>index===0 || values[index].gap || values[index-1].gap || (values[index].at && values[index-1].at && Date.parse(values[index].at!)-Date.parse(values[index-1].at!)>gapThreshold);
  let offset=0;
  const accessible=(index:number)=>`${values[index].label}: ${values[index].value.toLocaleString()} ${values[index].unit}${circular?` (${(values[index].value/(total ?? 1)*100).toFixed(1)}%)`:""}`;
  return <div className={`dashboard-diagram ${circular && widget.presentation.showLegend===true?"dashboard-diagram-circular":""}`}>
    {evidence.partial?<span className="dashboard-state-badge">Partial authorized scope</span>:null}
    {recordedGaps?<span className="dashboard-state-badge">Recorded gaps</span>:null}
    <svg viewBox={circular?"45 0 110 100":"-20 -5 225 125"} preserveAspectRatio={circular?"xMidYMid meet":"none"} role="group" aria-label={`${kind} recorded evidence`}>
      {!circular && kind!=="stacked-bar" && kind!=="horizontal-bar" && widget.presentation.showAxes!==false?<g className="dashboard-diagram-axis" aria-hidden="true">{[0,.5,1].map(fraction=>{const value=min+(max-min)*fraction;return <g key={fraction}><line x1="10" x2="190" y1={y(value)} y2={y(value)}/><text x="6" y={y(value)+2} textAnchor="end">{value.toLocaleString(undefined,{maximumFractionDigits:1,notation:"compact"})}</text></g>;})}<text x="10" y="105">{values[0]?.at?new Date(values[0].at).toLocaleDateString(undefined,{month:"short",day:"numeric"}):""}</text><text x="190" y="105" textAnchor="end">{values[values.length-1]?.at?new Date(values[values.length-1].at!).toLocaleDateString(undefined,{month:"short",day:"numeric"}):""}</text><text x="10" y="-1">{values[0]?.unit}</text></g>:null}
      {circular?values.map((item,index)=>{
        const fraction=item.value/(total ?? 1),start=offset;offset+=fraction;
        const polar=(v:number)=>[100+44*Math.sin(v*2*Math.PI),50-44*Math.cos(v*2*Math.PI)];
        const a=polar(start),b=polar(offset);
        return <g key={item.label} tabIndex={0} role="img" aria-label={accessible(index)} onFocus={()=>setFocused(index)} onBlur={()=>setFocused(null)}>
          {fraction===1?<circle cx="100" cy="50" r="44" fill={colors[index%colors.length]}/>:fraction>0?<path d={`M100 50 L${a.join(" ")} A44 44 0 ${fraction>0.5?1:0} 1 ${b.join(" ")} Z`} fill={colors[index%colors.length]}/>:null}<title>{accessible(index)}</title>
        </g>;
      }):kind==="stacked-bar"?values.map((item,index)=>{const start=offset;offset+=item.value/(total ?? 1);return <rect key={item.label} x={10+start*180} y="25" width={item.value/(total ?? 1)*180} height="50" fill={colors[index%colors.length]} tabIndex={0} role="img" aria-label={accessible(index)} onFocus={()=>setFocused(index)} onBlur={()=>setFocused(null)}/>;}):<>
        {kind==="area" && values.map((item,index)=>disconnected(index)?null:<path key={index} d={`M${x(index-1)} ${y(0)} L${x(index-1)} ${y(values[index-1].value)} L${x(index)} ${y(item.value)} L${x(index)} ${y(0)} Z`} fill="currentColor" opacity="0.18"/>)}
        {(kind==="line" || kind==="area") && values.map((item,index)=>disconnected(index)?null:<line key={index} x1={x(index-1)} y1={y(values[index-1].value)} x2={x(index)} y2={y(item.value)} stroke="currentColor" strokeWidth="1.5"/>)}
        {values.map((item,index)=><g key={index} tabIndex={0} role="img" aria-label={accessible(index)} onFocus={()=>setFocused(index)} onBlur={()=>setFocused(null)}>
          {kind==="horizontal-bar"?<rect x={10+Math.min(0,item.value)/(max-min)*180-min/(max-min)*180} y={5+index/values.length*90} width={Math.abs(item.value)/(max-min)*180} height={Math.max(1,80/values.length)} fill={colors[index%colors.length]}/>:kind==="bar"?<rect x={10+index/values.length*180} y={Math.min(y(item.value),y(0))} width={Math.max(1,160/values.length)} height={Math.abs(y(item.value)-y(0))} fill={colors[index%colors.length]}/>:<circle cx={x(index)} cy={y(item.value)} r="2" fill="currentColor"/>}<title>{accessible(index)}</title>
        </g>)}
      </>}
      {kind==="donut"?<circle cx="100" cy="50" r="27" fill="var(--panel-bg, #fff)" pointerEvents="none"/>:null}
    </svg>
    {focused!==null?<output>{accessible(focused)}</output>:null}
    {circular && total!==undefined?<p className="dashboard-diagram-total"><strong>{total.toLocaleString(undefined,{maximumFractionDigits:1})}</strong> {widget.type==="energy-period"?"kWh · covered consumption":"equipment in this scope"}</p>:null}
    {widget.presentation.showLegend===true?<ul className="dashboard-diagram-legend">{values.map((item,index)=><li key={index} title={accessible(index)}><span className="dashboard-diagram-swatch" style={{background:colors[index%colors.length]}} aria-hidden="true"/>{widget.type==="time-series"?new Date(item.at!).toLocaleTimeString(undefined,{hour:"2-digit",minute:"2-digit"}):item.label.replace(/^DEMO /,"")}: {item.value.toLocaleString(undefined,{maximumFractionDigits:1})} {item.unit}</li>)}</ul>:null}
  </div>;
}

