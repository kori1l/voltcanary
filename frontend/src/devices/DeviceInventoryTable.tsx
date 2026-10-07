import { Link } from "react-router-dom";
import { Device } from "./devicesApi";
import { LatestTelemetry } from "../telemetry/telemetryApi";
import { RiskResponse } from "../analytics/analyticsApi";
import { TimeDisplay } from "../components/TimeDisplay";
import { formatEnum } from "./details/deviceDetailsFormatting";
export function DeviceInventoryTable({ devices, locationById, typeByCode, latestByDevice, riskByDevice }: { devices: Device[]; locationById: Map<string,string>; typeByCode: Map<string,string>; latestByDevice: Record<string,LatestTelemetry | null>; riskByDevice: Record<string,RiskResponse | null> }) {
  return (<div className="console-table-region" role="region" aria-label="Device inventory" tabIndex={0}><table><thead><tr><th>Device</th><th>Location</th><th>Type</th><th>Registration</th><th>Last telemetry</th><th>Risk</th></tr></thead><tbody>{devices.map((device) => <tr key={device.id}><td><Link to={`/devices/${device.id}`}>{device.name}</Link></td><td>{locationById.get(device.locationId ?? "") ?? "Unassigned"}</td><td>{typeByCode.get(device.type) ?? formatEnum(device.type)}</td><td>{formatEnum(device.status)}</td><td>{latestByDevice[device.id] ? <TimeDisplay value={latestByDevice[device.id]!.timestamp} /> : "Unavailable"}</td><td>{riskByDevice[device.id] ? `${formatEnum(riskByDevice[device.id]!.level)} · ${riskByDevice[device.id]!.score} / 100` : "Unavailable"}</td></tr>)}</tbody></table></div>);
}
