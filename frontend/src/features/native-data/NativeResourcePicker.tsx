import { useEffect, useState } from "react";
import { listDevices, listLocations } from "../../devices/devicesApi";

export type BrowsableResource = "DEVICE" | "LOCATION";
const pageSize = 25;

export function NativeResourcePicker({ kind, field, onSelect }: {
  kind: BrowsableResource;
  field: string;
  onSelect: (id: string) => void;
}) {
  const [search, setSearch] = useState("");
  const [request, setRequest] = useState<{ search: string; offset: number } | null>(null);
  const [page, setPage] = useState<{ items: { id: string; name: string }[]; hasNext: boolean } | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!request) return;
    const controller = new AbortController();
    const params = { ...request, limit: pageSize };
    const load = kind === "DEVICE"
      ? listDevices(params, controller.signal).then(devices => ({
        items: devices.filter(device => device.status !== "DECOMMISSIONED"), hasNext: devices.length === pageSize,
      }))
      : listLocations(params, controller.signal).then(locations => ({ items: locations, hasNext: locations.length === pageSize }));
    void load.then(result => {
      if (!controller.signal.aborted) setPage(result);
    }).catch(failure => {
      if (!controller.signal.aborted) setError((failure as Error).message);
    });
    return () => controller.abort();
  }, [kind, request]);

  function load(value: string, offset: number) {
    setPage(null);
    setError(null);
    setRequest({ search: value, offset });
  }

  const resource = kind === "DEVICE" ? "devices" : "locations";
  if (!request) return <button type="button" className="ghost-button" onClick={() => load(search, 0)}>Browse {resource}</button>;
  return <section className="form-stack" aria-label={`${resource} for ${field}`}>
    <label>Search {resource}
      <input type="search" value={search} onChange={event => setSearch(event.target.value)}
        onKeyDown={event => {
          if (event.key !== "Enter") return;
          event.preventDefault();
          load(search.trim(), 0);
        }}/>
    </label>
    <div className="action-row">
      <button type="button" className="ghost-button" onClick={() => load(search.trim(), 0)}>Search</button>
      <button type="button" className="ghost-button" onClick={() => setRequest(null)}>Close picker</button>
    </div>
    {!page && !error ? <p role="status">Loading {resource}…</p> : null}
    {error ? <p role="alert">{error}</p> : null}
    {page?.items.length ? <label>Available {resource}
      <select value="" onChange={event => {
        onSelect(event.target.value);
        setRequest(null);
      }}>
        <option value="" disabled>Select a resource</option>
        {page.items.map(item => <option key={item.id} value={item.id}>{item.name} · {item.id}</option>)}
      </select>
    </label> : page ? <p>No available {resource} on this page.</p> : null}
    {request.offset > 0 || page?.hasNext ? <div className="action-row">
      {request.offset > 0 ? <button type="button" className="ghost-button"
        onClick={() => load(request.search, request.offset - pageSize)}>Previous {resource}</button> : null}
      {page?.hasNext ? <button type="button" className="ghost-button"
        onClick={() => load(request.search, request.offset + pageSize)}>Next {resource}</button> : null}
    </div> : null}
  </section>;
}
