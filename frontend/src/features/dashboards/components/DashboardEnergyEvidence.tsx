import type {DashboardWidget} from "../../../dashboards/dashboardsApi";
type Row=Record<string,unknown>;
const record=(value:unknown):Row=>value && typeof value==="object" && !Array.isArray(value)?value as Row:{};
const rows=(value:unknown):Row[]=>Array.isArray(value)?value.map(record):[];
const number=(value:unknown)=>typeof value==="number" && Number.isFinite(value)?value.toLocaleString(undefined,{maximumFractionDigits:3}):"Unavailable";
const percent=(value:unknown)=>typeof value==="number" && Number.isFinite(value) && value>=0 && value<=1?`${(value*100).toLocaleString(undefined,{maximumFractionDigits:1})}%`:"Unavailable";

export function DashboardEnergyEvidence({widget,data}:{widget:DashboardWidget;data:Row}){
  const energy=record(data.energy),baseline=record(data.baselineEnergy),comparison=record(data.comparison);
  const methods=[...new Set(rows(energy.devices).flatMap(device=>rows(device.epochs).map(epoch=>epoch.method)))];
  const locations=new Map(rows(data.locations).map(location=>[location.id,location.name]));
  const limit=Math.min(50,Math.max(1,Number(widget.query?.rows) || 20)),offset=Math.max(0,Number(widget.query?.offset) || 0);
  function ranking(title:string,entries:Row[],peak=false){return <section><h4>{title}</h4><p>Showing {Math.min(limit,Math.max(0,entries.length-offset))} of {entries.length} authorized entries. Totals use the full evaluated scope.</p>{entries.length?<div className="dashboard-table-scroll" role="region" aria-label={title} tabIndex={0}><table><thead><tr><th>Equipment</th><th>{peak?"Recorded peak (W)":"Covered energy (kWh)"}</th><th>Coverage</th>{peak?<th>Measured</th>:null}</tr></thead><tbody>{entries.slice(offset,offset+limit).map((entry,index)=><tr key={String(entry.deviceId ?? index)}><td>{String(entry.deviceName ?? "Recorded equipment")}</td><td>{number(entry[peak?"maximumPowerW":"energyKWh"])}</td><td>{String(entry.coverage ?? "UNKNOWN")} · {percent(entry.coverageFraction)}</td>{peak?<td>{typeof entry.maximumPowerAt==="string"?entry.maximumPowerAt:"Unavailable"}</td>:null}</tr>)}</tbody></table></div>:<p>No qualified recorded entries in this period.</p>}</section>;}
  return <>
    <p className="dashboard-primary-value">{number(energy.energyKWh)} <span>kWh</span></p>
    {widget.presentation?.showCalculation===true?<p>{energy.estimate?"Estimate from the saved method and declared interpolation":"Counter-derived consumption"} · {String(energy.coverage ?? "UNKNOWN")} coverage</p>:null}
    {energy.coverage!=="COMPLETE"?<span className="dashboard-state-badge">{String(energy.coverage ?? "UNKNOWN")} coverage · {percent(energy.coverageFraction)}</span>:null}{energy.estimate?<span className="dashboard-state-badge">Estimate</span>:null}
    {widget.presentation?.showLegend===true?<dl className="dashboard-kpis"><div><dt>Meter-time coverage</dt><dd>{percent(energy.coverageFraction)}</dd></div><div><dt>Equipment with energy evidence</dt><dd>{number(energy.devicesWithEvidence)} / {number(energy.evaluatedDevices)}</dd></div></dl>:null}
    {widget.presentation?.showCalculation===true?<><p>Saved methods: {methods.map(method=>method==="COUNTER_DELTA"?"Counter difference":method==="POWER_INTEGRAL"?"Power over elapsed time":"Recorded method").join(", ") || "Unavailable"}. Raw consumption includes maintenance.</p>
    <p>Gaps, resets, missing boundaries and unconfigured meters stay outside covered energy. Partial totals are not complete-period consumption.</p></>:null}
    {widget.presentation?.showComparison===true && data.baselineEnergy?<p>Change in covered energy: {number(comparison.difference)} kWh · {comparison.percent===null?"Percentage unavailable (zero or unavailable baseline)":`${number(comparison.percent)}%`}. Previous-period coverage: {percent(baseline.coverageFraction)} · {String(baseline.coverage ?? "UNKNOWN")}{baseline.estimate?" · Estimate":""}.</p>:null}
    {widget.type==="peak-and-ranking"?<>{ranking("Energy ranking",rows(energy.energyRanking))}{ranking("Recorded equipment peak ranking",rows(energy.peakRanking),true)}<p>Peaks belong to individual equipment at recorded times; they are not a simultaneous fleet peak.</p></>:null}
    {widget.presentation?.showDetails===true && rows(energy.locations).length?<details><summary>Energy by location</summary><div className="dashboard-table-scroll" role="region" aria-label="Energy by location" tabIndex={0}><table><thead><tr><th>Location</th><th>Covered energy (kWh)</th><th>Meter-time coverage</th></tr></thead><tbody>{rows(energy.locations).map((location,index)=><tr key={index}><td>{location.locationId==="UNASSIGNED"?"Unassigned":String(locations.get(location.locationId) ?? "Recorded location")}</td><td>{number(location.energyKWh)}</td><td>{String(location.coverage ?? "UNKNOWN")} · {percent(location.coverageFraction)}</td></tr>)}</tbody></table></div></details>:null}
    {widget.presentation?.showCalculation===true?<small>Physical meter independence comes from reviewed registry metadata. Known duplicate/overlapping meters are rejected; hidden physical overlap is not inferred.</small>:null}
  </>;
}
