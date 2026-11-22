import {useEffect,useState} from "react";
import {getTelemetryFields,type TelemetryField} from "../../../telemetry/telemetryFieldsApi";
export function TelemetryFieldTable({deviceId}:{deviceId:string}){
  const [fields,setFields]=useState<TelemetryField[]>([]),[error,setError]=useState("");
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const controller = new AbortController();
    setFields([]);
    setError("");
    async function read() {
      try {
        const values = await getTelemetryFields(deviceId, undefined, controller.signal);
        if (active) { setFields(values); setError(""); }
      } catch (failure) {
        if (active && !controller.signal.aborted) setError(String(failure));
      } finally {
        // Start the next interval after completion: never overlap reads or accept an older response last.
        if (active) timer = setTimeout(() => void read(), 5000);
      }
    }
    void read();
    return () => { active = false; controller.abort(); if (timer !== undefined) clearTimeout(timer); };
  }, [deviceId]);
  return <section className="panel"><h2>Stored telemetry fields</h2>{error?<p role="status">{error}</p>:null}<div className="telemetry-field-table"><table><thead><tr><th>Field</th><th>Value</th><th>Type</th><th>Source</th><th>Observed</th></tr></thead><tbody>{fields.map(field=><tr key={`${field.sourceId}:${field.key}`}><td>{field.label}<small> {field.key}</small></td><td>{typeof field.value==="boolean"?(field.value?"True":"False"):String(field.value)} {field.unit}</td><td>{field.type}</td><td>{field.sourceId}</td><td>{new Date(field.recordedAt).toLocaleTimeString()}</td></tr>)}</tbody></table></div>{!fields.length&&!error?<p>No stored fields yet.</p>:null}</section>;
}
