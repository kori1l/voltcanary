import { Link } from "react-router-dom";
import { Device } from "./devicesApi";
import { LatestTelemetry } from "../telemetry/telemetryApi";
import { RiskResponse } from "../analytics/analyticsApi";
import { TimeDisplay } from "../components/TimeDisplay";
import { formatEnum } from "./details/deviceDetailsFormatting";
import { SectionFeedback } from "../components/SectionFeedback";
import type { useInventoryEvidence } from "./useInventoryEvidence";
export function DeviceInventoryTable({ devices, locationById, typeByCode, latestByDevice, riskByDevice, evidence }: { devices: Device[]; locationById: Map<string,string>; typeByCode: Map<string,string>; latestByDevice: Record<string,LatestTelemetry | null>; riskByDevice: Record<string,RiskResponse | null>; evidence: ReturnType<typeof useInventoryEvidence> }) {
  return <div className="console-table-region inventory-table" role="region" aria-label="Device inventory" tabIndex={0}><table><thead><tr><th>Device</th><th>Location</th><th>Type</th><th>Registration</th><th>Last telemetry</th><th>Risk</th></tr></thead><tbody>{devices.map((device) => <tr key={device.id}>
    <td data-label="Device"><Link to={`/devices/${device.id}`}>{device.name}</Link></td><td data-label="Location">{locationById.get(device.locationId ?? "") ?? "Unassigned"}</td><td data-label="Type">{typeByCode.get(device.type) ?? formatEnum(device.type)}</td><td data-label="Registration">{formatEnum(device.status)}</td>
    <td data-label="Last telemetry">{latestByDevice[device.id] ? <TimeDisplay value={latestByDevice[device.id]!.timestamp} /> : !evidence.telemetry[device.id] || evidence.telemetry[device.id].status === "loading" ? "Pending telemetry" : evidence.telemetry[device.id].status === "empty" ? "No readings" : "Unavailable"}{evidence.telemetry[device.id] ? <SectionFeedback resource={evidence.telemetry[device.id]} label="telemetry" context={device.name} showFreshness onRetry={() => void evidence.reloadTelemetry(device.id)} /> : null}</td>
    <td data-label="Risk">{riskByDevice[device.id] ? `${formatEnum(riskByDevice[device.id]!.level)} · ${riskByDevice[device.id]!.score} / 100` : !evidence.risk[device.id] || evidence.risk[device.id].status === "loading" ? "Pending risk" : "Unavailable"}{evidence.risk[device.id] ? <SectionFeedback resource={evidence.risk[device.id]} label="risk" context={device.name} showFreshness onRetry={() => void evidence.reloadRisk(device.id)} /> : null}</td>
  </tr>)}</tbody></table></div>;
}
