import type { ReactNode } from "react";
import { Tabs } from "../../components/Tabs";
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
  children?: ReactNode;
}

export function DeviceTabs({ activeTab, onChange, children }: DeviceTabsProps) {
  return <Tabs label="Device sections" items={tabs} value={activeTab} onChange={onChange} mobileSelector>{children}</Tabs>;
}
