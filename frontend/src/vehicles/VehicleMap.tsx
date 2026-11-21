import {VehicleCurrentSpeed} from "./VehicleCurrentSpeed";
import {PositionFieldDetails} from "./PositionFieldDetails";
import {useEffect,useRef,useState} from "react";
import type {Map as LibreMap,Marker,GeoJSONSource} from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import type {PositionEvidence} from "./positionApi";
import {positionSegments} from "./positionGeometry";
import {zonePolygons} from "./zoneGeometry";
import type {VehicleZone} from "./monitoringModel";
import "maplibre-gl/dist/maplibre-gl.css";
import "./vehicle.css";

export function VehicleMap({evidence,initialFollow=true,zones=[],onPickPosition,visibleFields,compact=false,showDetails=true,showNotices=true,zoneEditing=false}:{zoneEditing?:boolean;evidence:PositionEvidence|null;initialFollow?:boolean;zones?:VehicleZone[];onPickPosition?:(latitude:number,longitude:number)=>void;visibleFields?:string[];compact?:boolean;showDetails?:boolean;showNotices?:boolean}) {
  const container=useRef<HTMLDivElement>(null),map=useRef<LibreMap|null>(null),marker=useRef<Marker|null>(null);
  const [follow,setFollow]=useState(initialFollow),[error,setError]=useState<string|null>(null),[retry,setRetry]=useState(0),[ready,setReady]=useState(false);
  const position=evidence?.latest.position,hasPosition=Boolean(position),hasMap=hasPosition||zoneEditing,stale=evidence?.latest.freshness!=="FRESH";
  const current=useRef(evidence);current.current=evidence;
  const pick=useRef(onPickPosition);pick.current=onPickPosition;
  useEffect(()=>{
    if(!hasMap || !container.current)return;
    let disposed=false;
    let observer:ResizeObserver|undefined;
    setError(null);setReady(false);
    void import("maplibre-gl").then(lib=>{
      if(disposed || !container.current)return;
      lib.setWorkerUrl(workerUrl);
      const point=current.current?.latest.position ?? zones[0] ?? {longitude:20.4612,latitude:44.8125};
      const instance=new lib.Map({container:container.current,style:import.meta.env.VITE_MAP_STYLE_URL || "https://tiles.openfreemap.org/styles/bright",center:[point.longitude,point.latitude],zoom:15});
      map.current=instance;
      instance.addControl(new lib.NavigationControl({showCompass:false}),"top-right");
      instance.on("error",()=>{if(!disposed)setError("Map unavailable. GPS measurements remain available below.");});
      instance.on("dragstart",event=>{if(event.originalEvent)setFollow(false);});
      instance.on("click",event=>{pick.current?.(event.lngLat.lat,event.lngLat.lng);});
      instance.on("load",()=>{
        if(disposed)return;
        const arrow=document.createElement("div");arrow.className="vehicle-marker";arrow.textContent="▲";arrow.setAttribute("aria-label","Vehicle position");
        if(current.current?.latest.position) marker.current=new lib.Marker({element:arrow,rotationAlignment:"map"}).setLngLat([point.longitude,point.latitude]).addTo(instance);
        instance.addSource("vehicle-track",{type:"geojson",data:{type:"FeatureCollection",features:[]}});
        instance.addLayer({id:"vehicle-track",type:"line",source:"vehicle-track",paint:{"line-color":"#2563eb","line-width":4}});
        instance.addSource("vehicle-zones",{type:"geojson",data:{type:"FeatureCollection",features:[]}});
        instance.addLayer({id:"vehicle-zones-fill",type:"fill",source:"vehicle-zones",paint:{"fill-color":"#8b5cf6","fill-opacity":0.15}});
        instance.addLayer({id:"vehicle-zones-line",type:"line",source:"vehicle-zones",paint:{"line-color":"#7c3aed","line-width":2}});
        const points=current.current?.history.points ?? [];
        if(points.length>1) {
          const bounds=new lib.LngLatBounds([point.longitude,point.latitude],[point.longitude,point.latitude]);
          points.forEach(p=>bounds.extend([p.longitude,p.latitude]));
          instance.fitBounds(bounds,{padding:40,maxZoom:16,duration:0});
        }
        if(!current.current?.latest.position&&zones.length){
          const bounds=new lib.LngLatBounds();
          zonePolygons(zones).forEach(feature=>feature.geometry.coordinates[0].forEach(coordinate=>bounds.extend(coordinate as [number,number])));
          instance.fitBounds(bounds,{padding:40,maxZoom:16,duration:0});
        }
        setReady(true);
      });
      observer=new ResizeObserver(()=>instance.resize());observer.observe(container.current);
    }).catch(()=>{if(!disposed)setError("Map unavailable. GPS measurements remain available below.");});
    return()=>{disposed=true;observer?.disconnect();marker.current?.remove();marker.current=null;map.current?.remove();map.current=null;};
  },[hasMap,hasPosition,retry]);
  useEffect(()=>{
    if(!ready || !map.current || !position)return;
    const instance=map.current;
    marker.current?.setLngLat([position.longitude,position.latitude]).setRotation(position.headingDeg ?? 0);
    const source=instance.getSource("vehicle-track") as GeoJSONSource|undefined;
    void source?.setData({type:"FeatureCollection",features:positionSegments(evidence?.history.points ?? []).map(coordinates=>({type:"Feature" as const,properties:{},geometry:{type:"LineString" as const,coordinates}}))});
    if(follow)instance.easeTo({center:[position.longitude,position.latitude],duration:500});
  },[evidence,follow,ready,position]);
  useEffect(()=>{if(!ready||!map.current)return;const source=map.current.getSource("vehicle-zones") as GeoJSONSource|undefined;void source?.setData({type:"FeatureCollection",features:zonePolygons(zones)});},[ready,zones]);
  return <section className={`vehicle-map-view${compact?" vehicle-map-view-compact":""}`} aria-label="Vehicle movement map">
    {hasMap?<><div className="vehicle-map-canvas" ref={container}/>{showDetails&&hasPosition?<label className="vehicle-follow"><input type="checkbox" checked={follow} onChange={event=>setFollow(event.target.checked)}/>Follow vehicle</label>:null}</>:<p role="status">Waiting for GPS measurements. Start the vehicle simulator in Demo Tools.</p>}
    {zoneEditing&&!hasPosition?<p role="status">No GPS position. Select a zone centre on the map; the map centre is not a vehicle observation.</p>:null}
    {error?<p role="alert">{error} <button type="button" className="ghost-button" onClick={()=>setRetry(value=>value+1)}>Retry map</button></p>:null}
    {position&&showDetails?<div className="vehicle-position-summary" aria-live="polite"><span>{evidence?.latest.freshness==="STALE"?"Stale position":"Recorded position"}</span>{visibleFields===undefined&&!Object.keys(position.fields??{}).length?<><span>{position.latitude.toFixed(5)}, {position.longitude.toFixed(5)}</span><VehicleCurrentSpeed latest={evidence?.latest}/></>:null}<time dateTime={position.recordedAt}>{new Date(position.recordedAt).toLocaleString()}</time></div>:null}
    {showNotices&&position&&stale?<p role="status">Текущая скорость недоступна. Последние измерения устарели — {new Date(position.recordedAt).toLocaleString()}.</p>:null}
    {position&&showDetails?compact?<details className="vehicle-map-details"><summary>Данные GPS и рейса</summary><PositionFieldDetails point={position} visibleFields={visibleFields} stale={stale}/></details>:<PositionFieldDetails point={position} visibleFields={visibleFields} stale={stale}/>:null}
    {showNotices&&evidence?.history.truncated?<p role="status">Latest 1000 points shown. Narrow the period to see the complete trail.</p>:null}
  </section>;
}
