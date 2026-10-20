import {useEffect, useState} from "react";
import {Link, useParams} from "react-router-dom";
import {getDevice, listDataSources, type Device, type DataSource} from "../devices/devicesApi";
import {VariableEditor} from "../features/device-variables/VariableEditor";

export function DeviceVariablesPage() {
  const {deviceId = ""} = useParams();
  const [device, setDevice] = useState<Device | null>(null), [sources, setSources] = useState<DataSource[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    let active = true;
    setDevice(null); setSources([]); setError("");
    void Promise.all([getDevice(deviceId), listDataSources(deviceId)]).then(([record, records]) => {
      if (active) {setDevice(record); setSources(records);}
    }).catch(failure => {if (active) setError(String(failure));});
    return () => {active = false;};
  }, [deviceId]);
  return <div className="page-stack">
    <Link to={`/devices/${deviceId}?tab=configuration`}>Back to device</Link>
    <h1>{device?.name ?? "Device"} · variables</h1>
    {error ? <p role="alert">{error}</p> : device ? <VariableEditor key={device.id} device={device} sources={sources} /> : <p role="status">Loading device variables…</p>}
  </div>;
}
