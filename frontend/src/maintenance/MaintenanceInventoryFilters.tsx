import { CompactEntityFilter } from "../components/CompactEntityFilter";
import type { EntityOption } from "../components/EntitySelector";
import type { EntityOptionLoader } from "../components/usePagedEntityOptions";
import { formatEnum } from "./calendarPresentation";
interface Props {
  search: string;
  onSearch: (value: string) => void;
  from: string;
  to: string;
  onFrom: (value: string) => void;
  onTo: (value: string) => void;
  location: string;
  locations: EntityOption[];
  loadLocations: EntityOptionLoader;
  onLocation: (id: string) => void;
  type: string;
  types: EntityOption[];
  onType: (value: string) => void;
  status: string;
  statuses: string[];
  onStatus: (value: string) => void;
  cancelled: boolean;
  onCancelled: (value: boolean) => void;
  onClear: () => void;
}
export function MaintenanceInventoryFilters(p: Props) {
  return (
    <section className="panel compact-console-controls">
      <div className="device-inventory-controls">
        <label className="compact-filter-search">
          <span className="sr-only">Search</span>
          <input
            type="search"
            value={p.search}
            onChange={(e) => p.onSearch(e.target.value)}
            placeholder="Device, type, location"
          />
        </label>
        <label>
          <span className="sr-only">From</span>
          <input type="date" value={p.from} onChange={(e) => p.onFrom(e.target.value)} />
        </label>
        <label>
          <span className="sr-only">To</span>
          <input type="date" value={p.to} onChange={(e) => p.onTo(e.target.value)} />
        </label>
        <CompactEntityFilter
          label="Location"
          allLabel="All locations"
          value={p.location === "all" ? "" : p.location}
          options={p.locations}
          loadOptions={p.loadLocations}
          onChange={p.onLocation}
        />
        <label>
          <span className="sr-only">Type</span>
          <select value={p.type} onChange={(e) => p.onType(e.target.value)}>
            <option value="all">All types</option>
            {p.types.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span className="sr-only">Status</span>
          <select value={p.status} onChange={(e) => p.onStatus(e.target.value)}>
            {p.statuses.map((s) => (
              <option key={s} value={s}>
                {s === "all" ? "All statuses" : formatEnum(s)}
              </option>
            ))}
          </select>
        </label>
        <button className="ghost-button" type="button" onClick={p.onClear}>
          Clear filters
        </button>
        <details className="device-inventory-more">
          <summary>Options</summary>
          <div className="device-inventory-popover">
            <label className="checkbox-row">
              <input
                type="checkbox"
                checked={p.cancelled}
                onChange={(e) => p.onCancelled(e.target.checked)}
              />
              Show cancelled
            </label>
            <small>
              Search and type filter the loaded operation page and roster. Continue through pages
              for later records.
            </small>
            <div className="maintenance-legend">
              {["Active", "Scheduled", "Finished", "Cancelled"].map((s) => (
                <span key={s} className="maintenance-legend-item">
                  <i className={`legend-dot legend-${s.toLowerCase()}`} />
                  {s}
                </span>
              ))}
            </div>
          </div>
        </details>
      </div>
    </section>
  );
}
