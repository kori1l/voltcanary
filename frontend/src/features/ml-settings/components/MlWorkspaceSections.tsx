import type { ReactNode } from "react";
import { Tabs, type TabItem } from "../../../components/Tabs";
export type MlSection = "settings" | "datasets" | "import" | "labels" | "training" | "models" | "policies" | "monitoring";
const items: TabItem<MlSection>[] = [{ value: "settings", label: "Prediction policy" }, { value: "datasets", label: "Datasets" }, { value: "import", label: "Import" }, { value: "labels", label: "Labels" }, { value: "training", label: "Training" }, { value: "models", label: "Models" }, { value: "policies", label: "Policies" }, { value: "monitoring", label: "Monitoring" }];
const id = "ml-workspace-sections";
export function MlSectionPicker({ value, onChange }: { value: MlSection; onChange: (value: MlSection) => void }) {
  return <label className="ml-section-picker">ML section<select value={value} aria-controls={`${id}-panel-${value}`} onChange={(event) => onChange(event.target.value as MlSection)}>{items.map((item) => <option key={item.value} value={item.value}>{item.label}</option>)}</select></label>;
}
export function MlWorkspaceSections({ value, onChange, children }: { value: MlSection; onChange: (value: MlSection) => void; children: ReactNode }) {
  return <Tabs id={id} label="ML workflow tabs" items={items} value={value} onChange={onChange}>{children}</Tabs>;
}
