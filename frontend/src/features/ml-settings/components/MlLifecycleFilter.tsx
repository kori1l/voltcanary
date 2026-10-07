import { MlLifecycleStatusFilter } from "../../../analytics/mlModuleApi";

import { lifecycleStatusFilters, humanizeKey } from "../model/mlConsolePresentation";
export function StatusFilterSelect(props: {
  label: string;
  value: MlLifecycleStatusFilter;
  onChange: (value: MlLifecycleStatusFilter) => Promise<void>;
}) {
  return (
    <label className="compact-select">
      {props.label}
      <select value={props.value} onChange={(event) => void props.onChange(event.target.value as MlLifecycleStatusFilter)}>
        {lifecycleStatusFilters.map((value) => <option key={value} value={value}>{humanizeKey(value)}</option>)}
      </select>
    </label>
  );
}

