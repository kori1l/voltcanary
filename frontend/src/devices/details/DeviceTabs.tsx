export type DeviceDetailsTab = "overview" | "telemetry" | "analytics" | "alerts" | "maintenance" | "configuration" | "simulation";

const tabs: { value: DeviceDetailsTab; label: string }[] = [
  { value: "overview", label: "Overview" },
  { value: "telemetry", label: "Telemetry" },
  { value: "analytics", label: "Analytics" },
  { value: "alerts", label: "Alerts" },
  { value: "maintenance", label: "Maintenance" },
  { value: "configuration", label: "Configuration" },
  { value: "simulation", label: "Demo Tools" },
];

interface DeviceTabsProps {
  activeTab: DeviceDetailsTab;
  onChange: (tab: DeviceDetailsTab) => void;
}

export function DeviceTabs({ activeTab, onChange }: DeviceTabsProps) {
  return (
    <nav className="tab-row" aria-label="Device sections">
      {tabs.map((tab) => (
        <button
          key={tab.value}
          className={activeTab === tab.value ? "tab-button tab-button-active" : "tab-button"}
          type="button"
          onClick={() => onChange(tab.value)}
        >
          {tab.label}
        </button>
      ))}
    </nav>
  );
}
