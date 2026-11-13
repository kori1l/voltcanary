import type {WidgetManifest} from "../../../dashboards/dashboardQueriesApi";
import {visualizations,type Visualization} from "./dashboardVisualization";

/** The service owns per-widget choices; the client only filters unknown renderer versions. */
export function widgetManifestVisualizations(manifest:WidgetManifest):Visualization[] {
  return (manifest.capabilities?.visualizations ?? manifest.visualizations ?? [])
    .filter((value):value is Visualization=>(visualizations as readonly string[]).includes(value));
}
