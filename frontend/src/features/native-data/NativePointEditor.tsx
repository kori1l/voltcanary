import { useEffect, useRef, useState } from "react";
import type { Map as LibreMap, Marker } from "maplibre-gl";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import "maplibre-gl/dist/maplibre-gl.css";
import type { CellDraft } from "./nativeDocuments";

type Point = [longitude: number, latitude: number];

function readPoint(longitude: string, latitude?: string): Point | null {
  if (!longitude.trim() || !latitude?.trim()) return null;
  const lng = Number(longitude);
  const lat = Number(latitude);
  return Number.isFinite(lng) && Math.abs(lng) <= 180 && Number.isFinite(lat) && Math.abs(lat) <= 90
    ? [lng, lat] : null;
}

export function NativePointEditor({ label, cell, onChange }: {
  label: string;
  cell: CellDraft;
  onChange: (value: Partial<CellDraft>) => void;
}) {
  const [open, setOpen] = useState(false);
  const point = readPoint(cell.value, cell.secondary);
  return <>
    <input aria-label={`${label} longitude`} required type="number" min={-180} max={180} step="any"
      placeholder="Longitude" value={cell.value} onChange={event => onChange({ value: event.target.value })}/>
    <input aria-label={`${label} latitude`} required type="number" min={-90} max={90} step="any"
      placeholder="Latitude" value={cell.secondary ?? ""} onChange={event => onChange({ secondary: event.target.value })}/>
    <button type="button" className="ghost-button" aria-expanded={open} onClick={() => setOpen(value => !value)}>
      {open ? "Close map" : "Choose on map"}
    </button>
    {open ? <PointMap label={label} point={point} onPick={([longitude, latitude]) =>
      onChange({ value: String(longitude), secondary: String(latitude) })}/> : null}
  </>;
}

function PointMap({ label, point, onPick }: { label: string; point: Point | null; onPick: (point: Point) => void }) {
  const container = useRef<HTMLDivElement>(null);
  const initialPoint = useRef(point);
  const pick = useRef(onPick);
  const [map, setMap] = useState<{ instance: LibreMap; marker: Marker } | null>(null);
  const [error, setError] = useState(false);

  useEffect(() => {
    let active = true;
    let instance: LibreMap | undefined;
    let observer: ResizeObserver | undefined;
    void import("maplibre-gl").then(lib => {
      if (!active || !container.current) return;
      lib.setWorkerUrl(workerUrl);
      instance = new lib.Map({
        container: container.current,
        style: import.meta.env.VITE_MAP_STYLE_URL || "https://tiles.openfreemap.org/styles/bright",
        center: initialPoint.current ?? [0, 0],
        zoom: initialPoint.current ? 13 : 1,
      });
      instance.addControl(new lib.NavigationControl({ showCompass: false }), "top-right");
      instance.on("click", ({ lngLat }) => {
        const { lng, lat } = lngLat.wrap();
        if (Number.isFinite(lng) && Number.isFinite(lat) && Math.abs(lat) <= 90) pick.current([lng, lat]);
      });
      instance.on("error", () => { if (active) setError(true); });
      observer = new ResizeObserver(() => instance?.resize());
      observer.observe(container.current);
      setMap({ instance, marker: new lib.Marker() });
    }).catch(() => { if (active) setError(true); });
    return () => { active = false; observer?.disconnect(); instance?.remove(); };
  }, []);

  useEffect(() => { pick.current = onPick; }, [onPick]);
  const longitude = point?.[0];
  const latitude = point?.[1];
  useEffect(() => {
    if (!map) return;
    if (longitude === undefined || latitude === undefined) map.marker.remove();
    else map.marker.setLngLat([longitude, latitude]).addTo(map.instance);
    return () => { map.marker.remove(); };
  }, [map, longitude, latitude]);

  return <section aria-label={`${label} map`}>
    <p>Select a location on the map or enter coordinates above.</p>
    <div className="native-point-map" ref={container}/>
    {error ? <p role="status">Some map content could not load. You can enter the coordinates above.</p> : null}
  </section>;
}
