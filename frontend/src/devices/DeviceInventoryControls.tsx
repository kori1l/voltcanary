import type { EntityOption } from "../components/EntitySelector";
import { CompactEntityFilter } from "../components/CompactEntityFilter";
import type { EntityOptionLoader } from "../components/usePagedEntityOptions";
import { ReadRefresh } from "../components/ReadRefresh";

interface Props {
  search: string;
  onSearch: (value: string) => void;
  location: string;
  locations: EntityOption[];
  loadLocations: EntityOptionLoader;
  onLocation: (id: string) => void;
  status: string;
  onStatus: (value: string) => void;
  view: string;
  onView: (value: string) => void;
  onClear: () => void;
  scope: string;
  refresh: () => Promise<unknown>;
}
export function DeviceInventoryControls(props: Props) {
  const filtered = Boolean(props.search || props.location !== "all" || props.status !== "all");
  return (
    <div className="device-inventory-controls" aria-label="Inventory controls">
      <input
        type="search"
        className="search-input"
        aria-label="Search devices"
        placeholder="Search devices…"
        value={props.search}
        onChange={(event) => props.onSearch(event.target.value)}
      />
      <CompactEntityFilter
        label="Filter by location"
        allLabel="All locations"
        value={props.location === "all" ? "" : props.location}
        options={props.locations}
        loadOptions={props.loadLocations}
        onChange={props.onLocation}
      />
      <label>
        <span className="sr-only">Registration status</span>
        <select
          aria-label="Filter by registration status"
          value={props.status}
          onChange={(event) => props.onStatus(event.target.value)}
        >
          <option value="all">All statuses</option>
          {["ACTIVE", "DISABLED", "OFFLINE", "DECOMMISSIONED"].map((status) => (
            <option key={status} value={status}>
              {status.charAt(0) + status.slice(1).toLowerCase()}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span className="sr-only">Inventory view</span>
        <select value={props.view} onChange={(event) => props.onView(event.target.value)}>
          <option value="cards">Cards by location</option>
          <option value="table">Compact table</option>
        </select>
      </label>
      <details className="device-inventory-more">
        <summary>Options</summary>
        <div className="device-inventory-popover">
          <ReadRefresh scope={props.scope} refresh={props.refresh} />
          <small>
            Registration status and telemetry freshness are separate. Counts refer to the current
            page.
          </small>
        </div>
      </details>
      {filtered ? (
        <button type="button" className="ghost-button device-clear-filters" onClick={props.onClear}>
          Clear filters
        </button>
      ) : null}
    </div>
  );
}
