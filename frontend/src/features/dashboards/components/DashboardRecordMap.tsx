import { useEffect, useRef, useState } from "react";
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import type { Map as LibreMap, Marker } from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";

export type RecordPoint = { label: string; longitude: number; latitude: number };

export function DashboardRecordMap({ points }: { points: RecordPoint[] }) {
  const container = useRef<HTMLDivElement>(null);
  const [error, setError] = useState(false);
  useEffect(() => {
    let active = true;
    let map: LibreMap | undefined;
    let observer: ResizeObserver | undefined;
    const markers: Marker[] = [];
    setError(false);
    void import("maplibre-gl").then(lib => {
      if (!active || !container.current || !points.length) return;
      lib.setWorkerUrl(workerUrl);
      const first = points[0];
      const instance = new lib.Map({
        container: container.current,
        style: import.meta.env.VITE_MAP_STYLE_URL || "https://tiles.openfreemap.org/styles/bright",
        center: [first.longitude, first.latitude],
        zoom: 12,
      });
      map = instance;
      instance.addControl(new lib.NavigationControl({ showCompass: false }), "top-right");
      instance.on("error", () => { if (active) setError(true); });
      const bounds = new lib.LngLatBounds();
      for (const point of points) {
        const coordinates: [number, number] = [point.longitude, point.latitude];
        bounds.extend(coordinates);
        const marker = new lib.Marker().setLngLat(coordinates).addTo(instance);
        marker.getElement().setAttribute("title", point.label);
        marker.getElement().setAttribute("aria-label", point.label);
        markers.push(marker);
      }
      if (points.length > 1) instance.fitBounds(bounds, { maxZoom: 13, padding: 30, duration: 0 });
      observer = new ResizeObserver(() => instance.resize());
      observer.observe(container.current);
    }).catch(() => { if (active) setError(true); });
    return () => {
      active = false;
      observer?.disconnect();
      markers.forEach(marker => marker.remove());
      map?.remove();
    };
  }, [points]);

  return <section aria-label="Recorded table locations">
    <div className="dashboard-record-map" ref={container}/>
    {error ? <p role="status">Map content is unavailable. Recorded locations are still visible in the table.</p> : null}
  </section>;
}
