import type {
  WidgetManifest,
  WidgetPresentationSetting,
} from "../../../dashboards/dashboardQueriesApi";

// Common controls also remain available for an unknown/older widget manifest.
const commonSettings: WidgetPresentationSetting[] = [
  {
    key: "showTitle",
    type: "BOOLEAN",
    section: "GENERAL",
    group: "Header",
    label: "Show panel title",
    defaultValue: false,
    choices: [],
    maxLength: null,
  },
  {
    key: "showDetails",
    type: "BOOLEAN",
    section: "GENERAL",
    group: "Evidence details",
    label: "Show measurement details below the panel",
    defaultValue: false,
    choices: [],
    maxLength: null,
  },
];

export function WidgetPresentationSettings({
  manifest,
  section,
  presentation,
  onChange,
}: {
  manifest: WidgetManifest;
  section: "GENERAL" | "APPEARANCE";
  presentation: Record<string, unknown>;
  onChange: (presentation: Record<string, unknown>) => void;
}) {
  const settings = (manifest.capabilities?.settings ?? commonSettings).filter(
    (setting) => setting.section === section && setting.type !== "FIELD_SET",
  );
  const groups = [...new Set(settings.map((setting) => setting.group))];
  function change(key: string, value: unknown) {
    const next = { ...presentation };
    if (value === undefined) delete next[key];
    else next[key] = value;
    onChange(next);
  }
  return (
    <>
      {groups.map((group) => (
        <section className="dashboard-setting-group" key={group} aria-label={group}>
          <h3>{group}</h3>
          {settings
            .filter((setting) => setting.group === group)
            .map((setting) => {
              const value = presentation[setting.key] ?? setting.defaultValue;
              if (setting.type === "BOOLEAN")
                return (
                  <label className="dashboard-presentation-toggle" key={setting.key}>
                    <input
                      type="checkbox"
                      checked={value === true}
                      onChange={(event) => change(setting.key, event.target.checked)}
                    />
                    {setting.label}
                  </label>
                );
              if (setting.type === "AUTO_BOOLEAN")
                return (
                  <label key={setting.key}>
                    {setting.label}
                    <select
                      value={
                        presentation[setting.key] == null
                          ? "auto"
                          : String(presentation[setting.key])
                      }
                      onChange={(event) =>
                        change(
                          setting.key,
                          event.target.value === "auto" ? undefined : event.target.value === "true",
                        )
                      }
                    >
                      {setting.choices.map((choice) => (
                        <option key={choice.value} value={choice.value}>
                          {choice.label}
                        </option>
                      ))}
                    </select>
                  </label>
                );
              if (setting.type === "SELECT")
                return (
                  <label key={setting.key}>
                    {setting.label}
                    <select
                      value={String(value ?? "")}
                      onChange={(event) => change(setting.key, event.target.value)}
                    >
                      {setting.choices.map((choice) => (
                        <option key={choice.value} value={choice.value}>
                          {choice.label}
                        </option>
                      ))}
                    </select>
                  </label>
                );
              return (
                <label key={setting.key}>
                  {setting.label}
                  <input
                    maxLength={setting.maxLength ?? undefined}
                    value={String(value ?? "")}
                    onChange={(event) => change(setting.key, event.target.value || undefined)}
                  />
                </label>
              );
            })}
        </section>
      ))}
    </>
  );
}
