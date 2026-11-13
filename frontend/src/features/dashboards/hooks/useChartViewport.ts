import {useLayoutEffect,useState} from "react";

/** SVG coordinates match CSS pixels so labels and markers retain their proportions. */
export function useChartViewport() {
  const [element,ref]=useState<SVGSVGElement|null>(null);
  const [size,setSize]=useState({width:640,height:220});
  useLayoutEffect(()=>{
    if(!element || typeof ResizeObserver==="undefined")return;
    const observer=new ResizeObserver(([entry])=>{
      const width=Math.max(1,Math.round(entry.contentRect.width)),height=Math.max(1,Math.round(entry.contentRect.height));
      if(entry.contentRect.width>0 && entry.contentRect.height>0)setSize(prior=>prior.width===width&&prior.height===height?prior:{width,height});
    });
    observer.observe(element);return ()=>observer.disconnect();
  },[element]);
  return {ref,...size};
}
