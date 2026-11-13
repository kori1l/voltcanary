import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
import type {WidgetManifest} from "../../../dashboards/dashboardQueriesApi";
import {MapFieldSelection} from "../../../vehicles/MapFieldSelection";
import {visualizationLabels,type Visualization} from "../model/dashboardVisualization";
import {WidgetPresentationSettings} from "./WidgetPresentationSettings";
import {widgetManifestVisualizations} from "../model/widgetManifestCapabilities";

export function WidgetAppearanceSettings({widget,manifest,onChange}:{widget:DashboardWidget;manifest:WidgetManifest;onChange:(widget:DashboardWidget)=>void}) {
  const choices=widgetManifestVisualizations(manifest);
  const presentation=(next:Record<string,unknown>)=>onChange({...widget,presentation:next});
  return <>
    {choices.length?<section className="dashboard-setting-group" aria-label="Visualization"><h3>Visualization</h3>
      <label>Visualization<select value={String(widget.presentation.visualization ?? "legacy")} onChange={event=>{
        const next={...widget.presentation};
        if(event.target.value==="legacy")delete next.visualization;else next.visualization=event.target.value;
        presentation(next);
      }}><option value="legacy">Default presentation</option>{choices.map(kind=><option key={kind} value={kind}>{visualizationLabels[kind as Visualization] ?? kind}</option>)}</select></label>
    </section>:null}
    <WidgetPresentationSettings manifest={manifest} section="APPEARANCE" presentation={widget.presentation} onChange={presentation}/>
    {manifest.capabilities?.settings.some(setting=>setting.type==="FIELD_SET"&&setting.key==="mapFields")?<section className="dashboard-setting-group" aria-label="Map tooltip fields"><h3>Map tooltip fields</h3><MapFieldSelection deviceIds={widget.binding.deviceIds??[]} selected={Array.isArray(widget.presentation.mapFields)?widget.presentation.mapFields as string[]:undefined} onChange={fields=>presentation({...widget.presentation,mapFields:fields})}/></section>:null}
    {widget.type==="prediction-evidence"?<small>Failure risk requires trained failure-prediction evidence. Confidence is not measured accuracy. Sorting applies to the returned page.</small>:null}
    {!choices.length&&!manifest.capabilities?.settings.some(setting=>setting.section==="APPEARANCE")?<p>Header and evidence details are available in General.</p>:null}
  </>;
}
