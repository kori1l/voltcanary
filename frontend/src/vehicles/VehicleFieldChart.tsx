import {useChartViewport} from "../features/dashboards/hooks/useChartViewport";

type Sample={at:string;value:number|boolean|string|null};
type Point={at:number;value:number;gap:boolean};

/** Connect only adjacent recorded observations; missing samples and long intervals break lines. */
export function recordedFieldPoints(samples:Sample[]):Point[] {
  const ordered=samples.map(sample=>({...sample,time:Date.parse(sample.at)})).filter(sample=>Number.isFinite(sample.time)).sort((a,b)=>a.time-b.time);
  let previous:number|null=null;
  return ordered.flatMap(sample=>{
    if(typeof sample.value!=="boolean" && !(typeof sample.value==="number" && Number.isFinite(sample.value))){previous=null;return [];}
    const gap=previous===null || sample.time-previous>30000;
    previous=sample.time;
    return [{at:sample.time,value:Number(sample.value),gap}];
  });
}

export function VehicleFieldChart({title,samples,from,to,falseLabel,trueLabel,truncated}:{title:string;samples:Sample[];from?:string;to?:string;falseLabel:string;trueLabel:string;truncated:boolean}) {
  const {ref,width,height}=useChartViewport();
  const points=recordedFieldPoints(samples),boolean=samples.some(sample=>typeof sample.value==="boolean");
  const first=Date.parse(from ?? ""),last=Date.parse(to ?? "");
  const start=Number.isFinite(first)?first:points[0]?.at ?? 0,end=Math.max(start+1,Number.isFinite(last)?last:points[points.length-1]?.at ?? start+1);
  const values=points.map(point=>point.value),low=boolean?0:Math.min(...values),high=boolean?1:Math.max(...values);
  const min=low===high?low-1:low,max=low===high?high+1:high;
  const left=76,right=Math.max(left+1,width-16),top=16,bottom=Math.max(top+1,height-38);
  const x=(at:number)=>left+(at-start)/(end-start)*(right-left),y=(value:number)=>bottom-(value-min)/(max-min)*(bottom-top);
  const formatTime=(at:number)=>new Date(at).toLocaleTimeString(undefined,{hour:"2-digit",minute:"2-digit"});
  if(!points.length)return <p role="status">Нет записанных числовых или логических значений для графика.</p>;
  return <div className="vehicle-field-chart">
    <svg ref={ref} viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${title}: записанные состояния`}>
      <g className="dashboard-series-axis">{[min,max].map(value=><g key={value}><line x1={left} x2={right} y1={y(value)} y2={y(value)}/><text x={left-8} y={y(value)+4} textAnchor="end">{boolean?value?trueLabel:falseLabel:value.toLocaleString(undefined,{maximumFractionDigits:1})}</text></g>)}
        <text x={left} y={height-12}>{formatTime(start)}</text><text x={right} y={height-12} textAnchor="end">{formatTime(end)}</text>
      </g>
      <path d={points.map(point=>`${point.gap?"M":"L"}${x(point.at)},${y(point.value)}`).join(" ")} fill="none" stroke="#3b82f6" strokeWidth="1.5"/>
      {points.map((point,index)=><circle key={`${point.at}:${index}`} cx={x(point.at)} cy={y(point.value)} r="2.5" fill="#3b82f6"><title>{new Date(point.at).toLocaleString()} · {boolean?point.value?trueLabel:falseLabel:point.value}</title></circle>)}
    </svg>
    {truncated?<small>Показаны последние 1000 наблюдений.</small>:null}
  </div>;
}
