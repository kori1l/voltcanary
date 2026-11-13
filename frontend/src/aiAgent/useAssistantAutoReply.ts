import {useEffect,useRef,useState} from "react";
import type {AssistantDefaultReply} from "./assistantDefaultReply";

export function useAssistantAutoReply(planId:string|undefined,reply:AssistantDefaultReply|null,busy:boolean,onReply:(message:string)=>void) {
  const [remaining,setRemaining]=useState(60),[canceled,setCanceled]=useState(false);
  const stopped=useRef(false),attempted=useRef(false),deadline=useRef(0),callback=useRef(onReply);
  callback.current=onReply;
  const message=reply?.message;
  useEffect(()=>{
    stopped.current=false;attempted.current=false;deadline.current=Date.now()+60_000;
    setCanceled(false);setRemaining(60);
  },[planId]);
  useEffect(()=>{
    if(!planId||!message||busy||canceled||attempted.current)return;
    const tick=()=>{
      if(stopped.current||attempted.current)return;
      const seconds=Math.max(0,Math.ceil((deadline.current-Date.now())/1000));
      setRemaining(seconds);
      if(seconds===0){attempted.current=true;callback.current(message);}
    };
    const timer=window.setInterval(tick,1000);
    return()=>window.clearInterval(timer);
  },[planId,message,busy,canceled]);
  const cancel=()=>{stopped.current=true;setCanceled(true);};
  return {remaining,cancel,visible:Boolean(planId&&reply&&!busy&&!canceled&&!attempted.current)};
}
