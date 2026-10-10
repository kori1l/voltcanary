import type {Layout} from "react-grid-layout";
const overlaps=(a:Layout[number],b:Layout[number])=>a.x<b.x+b.w && b.x<a.x+a.w && a.y<b.y+b.h && b.y<a.y+a.h;

/** Restore neighbours from the initial layout rather than the cursor's previous path. */
export function restoreGestureLayout(next:Layout,initial:Layout,activeId:string):Layout{
  const active=next.find((p)=>p.i===activeId);
  if(!active)return next;
  const originals=new Map(initial.map((p)=>[p.i,p]));
  const result=[{...active}];
  const neighbours=next.filter((p)=>p.i!==activeId).map((p)=>({...p,...originals.get(p.i)})).sort((a,b)=>a.y-b.y || a.x-b.x || a.i.localeCompare(b.i));
  for(const item of neighbours){
    while(result.some((p)=>overlaps(p,item)))item.y=Math.max(...result.filter((p)=>overlaps(p,item)).map((p)=>p.y+p.h));
    result.push(item);
  }
  return next.map((p)=>result.find((restored)=>restored.i===p.i)!);
}
