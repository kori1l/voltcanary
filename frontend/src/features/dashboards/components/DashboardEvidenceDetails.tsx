import {useState} from "react";
const privateKeys=new Set(["accountId","ownerUserId","authorizedDeviceIds"]);
export function evidenceLabel(key:string){return key.replace(/([A-Z])/g," $1").replace(/_/g," ").replace(/^./,(letter)=>letter.toUpperCase());}
function scalar(value:unknown){if(typeof value==="string" && /^\d{4}-\d{2}-\d{2}T/.test(value) && Number.isFinite(Date.parse(value)))return `${new Date(value).toLocaleString(undefined,{timeZone:"UTC"})} UTC`;return value===null || value===undefined?"Unavailable":typeof value==="number"?value.toLocaleString():typeof value==="boolean"?(value?"Yes":"No"):String(value);}
export function DashboardEvidenceDetails({value,depth=0}:{value:unknown;depth?:number}){
  const [limit,setLimit]=useState(20);
  if(value===null || typeof value!=="object")return <span className="dashboard-info-value">{scalar(value)}</span>;
  if(depth>6)return <span>Open the related working view for deeper records.</span>;
  if(Array.isArray(value)){
    if(!value.length)return <span>No recorded entries</span>;
    return <div className="dashboard-info-records"><small>{value.length} entries{value.length>limit?` · Showing ${limit}`:""}</small>{value.slice(0,limit).map((item,index)=>{
      if(!item || typeof item!=="object")return <div className="dashboard-info-value" key={index}>{scalar(item)}</div>;
      const row=item as Record<string,unknown>;const title=row.deviceName ?? row.name ?? row.title ?? row.metric ?? `Record ${index+1}`;
      return <details className="dashboard-info-record" key={index}><summary>{scalar(title)}</summary><DashboardEvidenceDetails value={item} depth={depth+1}/></details>;
    })}{value.length>limit?<button type="button" className="ghost-button" onClick={()=>setLimit((count)=>count+20)}>Show more entries</button>:null}</div>;
  }
  return <dl className="dashboard-info-fields">{Object.entries(value).filter(([key])=>!privateKeys.has(key)).map(([key,item])=><div key={key}><dt>{evidenceLabel(key)}</dt><dd><DashboardEvidenceDetails value={item} depth={depth+1}/></dd></div>)}</dl>;
}
