import {useEffect,useRef,useState} from "react";
import {EntitySelector,type EntityOption} from "./EntitySelector";
import type {EntityOptionLoader} from "./usePagedEntityOptions";

export function CompactEntityFilter({label,allLabel,value,options,loadOptions,onChange}:{label:string;allLabel:string;value:string;options:EntityOption[];loadOptions:EntityOptionLoader;onChange:(id:string)=>void}){
  const [open,setOpen]=useState(false);const box=useRef<HTMLDivElement>(null),button=useRef<HTMLButtonElement>(null);
  const id=`compact-${label.toLowerCase().replace(/\s+/g,"-")}`;
  const name=options.find(item=>item.id===value)?.name ?? (value?`Selected ${label.toLowerCase()}`:allLabel);
  useEffect(()=>{if(!open)return;const outside=(event:PointerEvent)=>{if(!box.current?.contains(event.target as Node))setOpen(false);};document.addEventListener("pointerdown",outside);return()=>document.removeEventListener("pointerdown",outside);},[open]);
  function select(value:string){onChange(value);setOpen(false);button.current?.focus();}
  return <div className="device-location-filter" ref={box} onKeyDown={event=>{if(event.key==="Escape"){setOpen(false);button.current?.focus();}}}><button ref={button} type="button" className="ghost-button" aria-label={`${label}: ${name}`} aria-expanded={open} aria-controls={id} onClick={()=>setOpen(!open)}>{name}<span aria-hidden="true">⌄</span></button>{open?<div id={id} className="device-inventory-popover"><EntitySelector label={label} options={options} value={value} loadOptions={loadOptions} onChange={select}/><button type="button" className="ghost-button" onClick={()=>select("")}>{allLabel}</button></div>:null}</div>;
}
