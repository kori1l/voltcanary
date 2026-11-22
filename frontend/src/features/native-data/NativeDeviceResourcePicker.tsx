import { useEffect, useState } from "react";
import { EntitySelector, type EntityOption } from "../../components/EntitySelector";
import { getDevice, listDataSources } from "../../devices/devicesApi";
import { dashboardDeviceOptions } from "../dashboards/model/dashboardEntityOptions";
import {
  listOperatingSchedules,
  listOperationalFacts,
} from "../dashboards/model/dashboardBusinessApi";
import { listVariables } from "../device-variables/variablesApi";

export const deviceResourceLabels = {
  SOURCE: "data sources",
  VARIABLE: "variables",
  SCHEDULE: "operating schedules",
  OPERATIONAL_FACT: "operational events",
};
type Kind = keyof typeof deviceResourceLabels;

export function isDeviceResource(value: string | null): value is Kind {
  return value !== null && Object.prototype.hasOwnProperty.call(deviceResourceLabels, value);
}

async function options(
  kind: Kind,
  device: string,
  from: string,
  until: string,
  signal: AbortSignal,
): Promise<EntityOption[]> {
  switch (kind) {
    case "SOURCE":
      return (await listDataSources(device, signal)).map((source) => ({
        id: source.id,
        name: source.externalId,
        status: source.protocol,
      }));
    case "VARIABLE": {
      const selected = await getDevice(device, signal);
      const variables = await listVariables(selected.type, signal);
      return variables
        .filter((variable) => !variable.archived)
        .map((variable) => ({ id: variable.id, name: variable.label, status: variable.unit }));
    }
    case "SCHEDULE":
      return (await listOperatingSchedules(device, signal)).flatMap((schedule) =>
        schedule.scheduleId
          ? [
              {
                id: schedule.scheduleId,
                name: `${schedule.effectiveFrom} – ${schedule.effectiveTo}`,
                status: schedule.timezone,
              },
            ]
          : [],
      );
    case "OPERATIONAL_FACT":
      return (
        await listOperationalFacts(device, from, until, undefined, undefined, signal)
      ).flatMap((fact) =>
        fact.eventId
          ? [
              {
                id: fact.eventId,
                name: `${fact.kind} · ${fact.startAt}`,
                status: fact.completeness,
              },
            ]
          : [],
      );
  }
}

export function NativeDeviceResourcePicker({
  kind,
  onSelect,
}: {
  kind: Kind;
  onSelect: (id: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [device, setDevice] = useState("");
  const [from, setFrom] = useState("");
  const [until, setUntil] = useState("");
  const [items, setItems] = useState<EntityOption[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const start = Date.parse(from);
  const end = Date.parse(until);
  const eligible =
    kind !== "OPERATIONAL_FACT" ||
    (Number.isFinite(start) && end > start && end - start <= 31 * 86_400_000);

  useEffect(() => {
    setItems(null);
    setError(null);
    if (!open || !device || !eligible) return;
    const controller = new AbortController();
    void options(
      kind,
      device,
      from ? `${from}T00:00:00Z` : "",
      until ? `${until}T00:00:00Z` : "",
      controller.signal,
    )
      .then((result) => {
        if (!controller.signal.aborted)
          setItems([...new Map(result.map((item) => [item.id, item])).values()]);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError((failure as Error).message);
      });
    return () => controller.abort();
  }, [open, kind, device, from, until, eligible]);

  const label = deviceResourceLabels[kind];
  if (!open)
    return (
      <button type="button" className="ghost-button" onClick={() => setOpen(true)}>
        Browse {label}
      </button>
    );
  return (
    <section className="form-stack" aria-label={`Choose from ${label}`}>
      <EntitySelector
        label="Resource device"
        options={[]}
        loadOptions={dashboardDeviceOptions}
        value={device}
        onChange={setDevice}
      />
      {kind === "OPERATIONAL_FACT" && device ? (
        <>
          <p>Choose a UTC period of up to 31 days.</p>
          <label>
            From date
            <input type="date" value={from} onChange={(event) => setFrom(event.target.value)} />
          </label>
          <label>
            Until date (exclusive)
            <input type="date" value={until} onChange={(event) => setUntil(event.target.value)} />
          </label>
        </>
      ) : null}
      {device && eligible && items === null && !error ? (
        <p role="status">Loading {label}…</p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {items?.length ? (
        <EntitySelector
          label={`Available ${label}`}
          options={items}
          value=""
          onChange={(id) => {
            if (!id) return;
            onSelect(id);
            setOpen(false);
          }}
        />
      ) : items ? (
        <p>No available {label} for the selection.</p>
      ) : null}
      <button type="button" className="ghost-button" onClick={() => setOpen(false)}>
        Close resource picker
      </button>
    </section>
  );
}
