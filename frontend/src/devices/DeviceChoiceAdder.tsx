import { useEffect, useRef, useState } from "react";
import { EntitySelector } from "../components/EntitySelector";
import { SectionFeedback } from "../components/SectionFeedback";
import { useSectionResource } from "../components/useSectionResource";
import { getDevice, type Device } from "./devicesApi";
import { loadDeviceOptions } from "./fleetEntityOptions";
export function DeviceChoiceAdder({ onAdd, label = "Add device to selection" }: { onAdd: (device: Device) => void; label?: string }) {
  const [selected, setSelected] = useState("");
  const [attempt, setAttempt] = useState(0);
  const resource = useSectionResource<Device>(selected || "none");
  const callback = useRef(onAdd); callback.current = onAdd;
  useEffect(() => {
    if (!selected) return;
    const controller = new AbortController();
    void resource.load(async () => { const result = await getDevice(selected, controller.signal); if (result.id !== selected || typeof result.name !== "string") throw new Error("Selected device details unavailable"); return result; }).then((device) => { if (device && !controller.signal.aborted) { callback.current(device); setSelected(""); } });
    return () => controller.abort();
  }, [selected, attempt, resource.load]);
  return <div><EntitySelector label={label} options={[]} value={selected} onChange={setSelected} loadOptions={loadDeviceOptions} /><SectionFeedback resource={resource} label="selected device details" onRetry={() => setAttempt((value) => value + 1)} /></div>;
}
