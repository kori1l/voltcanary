import { useEffect, useState } from "react";
import { listDataSources, type DataSource } from "../devices/devicesApi";

export function VehicleSourceSelector({
  deviceId,
  value,
  onChange,
}: {
  deviceId: string;
  value: string;
  onChange: (source: string) => void;
}) {
  const [sources, setSources] = useState<DataSource[]>([]),
    [error, setError] = useState<string | null>(null);
  useEffect(() => {
    setSources([]);
    setError(null);
    let active = true;
    if (deviceId)
      void listDataSources(deviceId)
        .then((items) => {
          if (active) setSources(items.filter((item) => item.protocol === "REST"));
        })
        .catch((err) => {
          if (active) setError(err instanceof Error ? err.message : "Sources unavailable");
        });
    return () => {
      active = false;
    };
  }, [deviceId]);
  return (
    <label>
      GPS source
      <select required value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">Select GPS source</option>
        {sources.map((source) => (
          <option key={source.id} value={source.externalId}>
            {source.externalId}
          </option>
        ))}
      </select>
      {error ? <span role="alert">{error}</span> : null}
    </label>
  );
}
