import {Settings2} from "lucide-react";
import {createTransportMarker,transportKind} from "./transportMarker";
import {fleetTrackGeometry} from "./fleetTrackGeometry";
import {useEffect,useRef,useState,useId} from "react";
import type {Map as LibreMap,Marker,GeoJSONSource} from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import type {LatestPosition,PositionHistory} from "./positionApi";
import "maplibre-gl/dist/maplibre-gl.css";
export interface FleetMarker {deviceId:string;name:string;latest:LatestPosition}
export function FleetMap({items,selected,onSelect,initialFollow=true,initialFit=false,tracks}:{items:FleetMarker[];selected:string;onSelect:(id:string)=>void;initialFollow?:boolean;initialFit?:boolean;tracks?:PositionHistory[]}) {
  const container=useRef<HTMLDivElement>(null),map=useRef<LibreMap|null>(null),markers=useRef(new Map<string,Marker>()),current=useRef(items),select=useRef(onSelect);
  const transportMarkers=useRef(new Map<string,ReturnType<typeof createTransportMarker>>());
  current.current=items;select.current=onSelect;
  const [ready,setReady]=useState(false),[error,setError]=useState<string|null>(null),[retry,setRetry]=useState(0),[follow,setFollow]=useState(initialFollow);
  const fitted=useRef(false);
  const [settingsOpen,setSettingsOpen]=useState(false);
  const settingsId=useId(),settingsRoot=useRef<HTMLDivElement>(null),settingsButton=useRef<HTMLButtonElement>(null);
  useEffect(()=>{if(!settingsOpen)return;const close=(event:PointerEvent)=>{if(!settingsRoot.current?.contains(event.target as Node))setSettingsOpen(false);};document.addEventListener("pointerdown",close);return()=>document.removeEventListener("pointerdown",close);},[settingsOpen]);
  const [showTracks,setShowTracks]=useState(true);
  const currentTracks=useRef(tracks);currentTracks.current=tracks;
  const fit=()=>{setFollow(false);const positions=[...current.current.flatMap(item=>item.latest.position?[item.latest.position]:[]),...(showTracks?(currentTracks.current??[]).flatMap(track=>track.points):[])];if(!map.current||!positions.length)return;
    const lons=positions.map(point=>point.longitude),lats=positions.map(point=>point.latitude);map.current.fitBounds([[Math.min(...lons),Math.min(...lats)],[Math.max(...lons),Math.max(...lats)]],{padding:60,maxZoom:15});};
  useEffect(()=>{
    if(!container.current)return;
    let disposed=false;let observer:ResizeObserver|undefined;
    setReady(false);setError(null);fitted.current=false;
    void import("maplibre-gl").then(lib=>{
      if(disposed||!container.current)return;
      lib.setWorkerUrl(workerUrl);const first=current.current.find(item=>item.latest.position)?.latest.position;
      const instance=new lib.Map({container:container.current,style:import.meta.env.VITE_MAP_STYLE_URL||"https://tiles.openfreemap.org/styles/bright",center:first?[first.longitude,first.latitude]:[20.4612,44.8125],zoom:13});map.current=instance;
      instance.addControl(new lib.NavigationControl(),"bottom-right");
      instance.on("load",()=>{if(disposed)return;
        instance.addSource("fleet-tracks",{type:"geojson",data:{type:"FeatureCollection",features:[]}});
        instance.addLayer({id:"fleet-track-casing",type:"line",source:"fleet-tracks",filter:["==",["get","kind"],"observed"],layout:{"line-cap":"round","line-join":"round"},paint:{"line-color":"#ffffff","line-width":["+",["get","width"],2],"line-opacity":0.7}});
        instance.addLayer({id:"fleet-track-gaps",type:"line",source:"fleet-tracks",filter:["==",["get","kind"],"gap"],paint:{"line-color":["get","color"],"line-width":2,"line-opacity":0.25}});
        instance.addLayer({id:"fleet-track-lines",type:"line",source:"fleet-tracks",filter:["==",["get","kind"],"observed"],layout:{"line-cap":"round","line-join":"round","line-sort-key":["get","order"]},paint:{"line-color":["get","color"],"line-width":["get","width"],"line-opacity":["get","opacity"]}});
        setReady(true);
      });instance.on("error",()=>{if(!disposed)setError("Fleet map unavailable. Vehicle measurements remain in the list.");});
      instance.on("dragstart",event=>{if(event.originalEvent)setFollow(false);});
      observer=new ResizeObserver(()=>instance.resize());observer.observe(container.current);
    }).catch(()=>{if(!disposed)setError("Fleet map unavailable. Vehicle measurements remain in the list.");});
    return()=>{disposed=true;observer?.disconnect();markers.current.forEach(marker=>marker.remove());markers.current.clear();transportMarkers.current.forEach(icon=>icon.dispose());transportMarkers.current.clear();map.current?.remove();map.current=null;};
  },[retry]);
  useEffect(()=>{
    if(!ready||!map.current)return;
    let active=true;
    void import("maplibre-gl").then(lib=>{
      if(!active||!map.current)return;
      const visible=new Set(items.filter(item=>item.latest.position).map(item=>item.deviceId));
      markers.current.forEach((marker,id)=>{if(!visible.has(id)){marker.remove();markers.current.delete(id);transportMarkers.current.get(id)?.dispose();transportMarkers.current.delete(id);}});
      items.forEach(item=>{const point=item.latest.position;if(!point)return;let marker=markers.current.get(item.deviceId);
        if(!marker){const icon=createTransportMarker(transportKind(item.latest.sourceId));transportMarkers.current.set(item.deviceId,icon);const element=icon.element;element.addEventListener("click",()=>select.current(item.deviceId));marker=new lib.Marker({element,rotationAlignment:"map"}).setLngLat([point.longitude,point.latitude]).addTo(map.current!);markers.current.set(item.deviceId,marker);}
        marker.setLngLat([point.longitude,point.latitude]);transportMarkers.current.get(item.deviceId)?.setHeading(point.headingDeg);marker.getElement().setAttribute("aria-label",`Select ${item.name}`);marker.getElement().setAttribute("title",`${item.name}: ${item.latest.freshness}`);marker.getElement().classList.toggle("selected",selected===item.deviceId);marker.getElement().classList.toggle("stale",item.latest.freshness!=="FRESH");
      });
      const point=items.find(item=>item.deviceId===selected)?.latest.position;
      if(follow&&point)map.current.easeTo({center:[point.longitude,point.latitude],duration:500});
    });
    return()=>{active=false;};
  },[items,selected,follow,ready]);
  useEffect(()=>{if(ready&&initialFit&&!fitted.current&&items.some(item=>item.latest.position)){fit();fitted.current=true;}},[ready,items,initialFit]);
  useEffect(()=>{if(ready&&map.current){const source=map.current.getSource("fleet-tracks") as GeoJSONSource|undefined;source?.setData(fleetTrackGeometry(showTracks?tracks??[]:[],selected));}},[ready,tracks,showTracks,selected]);
  return <section className="fleet-map" aria-label="Fleet map"><div className="fleet-map-surface"><div className="fleet-map-canvas" ref={container}/>
    <div className="fleet-map-settings" ref={settingsRoot} onKeyDown={event=>{if(event.key==="Escape"){setSettingsOpen(false);settingsButton.current?.focus();event.stopPropagation();}}}>
      <button type="button" ref={settingsButton} className="fleet-settings-toggle" aria-label="Map settings" title="Map settings" aria-expanded={settingsOpen} aria-controls={settingsId} onClick={()=>setSettingsOpen(value=>!value)}><Settings2 size={18}/></button>
      {settingsOpen?<div id={settingsId} className="fleet-settings-panel" role="group" aria-label="Map settings options">
        <strong>Map settings</strong><button type="button" className="fleet-fit-button" onClick={fit}>Fit all vehicles</button>
        {tracks?<label><input type="checkbox" checked={showTracks} onChange={event=>setShowTracks(event.target.checked)}/> Show tracks</label>:null}
        <label><input type="checkbox" checked={follow} onChange={event=>setFollow(event.target.checked)}/> Follow selected</label>
        {tracks?<small>Tracks · last 15 minutes · {tracks.reduce((sum,track)=>sum+track.points.length,0)} GPS points.<br/>Faint lines connect gaps in GPS data.{tracks.some(track=>track.truncated)?" Some tracks are truncated.":""}</small>:null}
      </div>:null}
    </div></div>
    {tracks&&showTracks&&!tracks.some(track=>track.points.length>1)?<small>Not enough GPS history yet.</small>:null}
    {error?<p role="alert">{error} <button onClick={()=>setRetry(value=>value+1)}>Retry map</button></p>:null}</section>;
}
