import { useEffect, useId, useRef, useState, type ReactNode } from "react";
export interface TabItem<T extends string> { value: T; label: string; disabled?: boolean }
/** Manual activation keeps arrow navigation from starting heavy workflow reads. */
export function Tabs<T extends string>({ id: suppliedId, label, items, value, onChange, children, mobileSelector = false }: { id?: string; label: string; items: TabItem<T>[]; value: T; onChange: (value: T) => void; children: ReactNode; mobileSelector?: boolean }) {
  const generatedId = useId(); const id = suppliedId ?? generatedId;
  const [focused, setFocused] = useState(value);
  const buttons = useRef(new Map<T, HTMLButtonElement>());
  useEffect(() => setFocused(value), [value]);
  function move(key: string) {
    const enabled = items.filter((item) => !item.disabled);
    const index = enabled.findIndex((item) => item.value === focused);
    const next = key === "Home" ? enabled[0] : key === "End" ? enabled[enabled.length - 1] : enabled[(index + (key === "ArrowRight" ? 1 : -1) + enabled.length) % enabled.length];
    if (next) { setFocused(next.value); buttons.current.get(next.value)?.focus(); }
  }
  return <div className="console-tabs">
    {mobileSelector ? <label className="mobile-section-picker">{label}<select value={value} aria-controls={`${id}-panel-${value}`} onChange={(event) => onChange(event.target.value as T)}>{items.map((item) => <option key={item.value} value={item.value} disabled={item.disabled}>{item.label}</option>)}</select></label> : null}
    <div className="tab-row" role="tablist" aria-label={label}>
      {items.map((item) => <button key={item.value} id={`${id}-tab-${item.value}`} ref={(button) => { if (button) buttons.current.set(item.value, button); else buttons.current.delete(item.value); }} role="tab" type="button" className={value === item.value ? "tab-button tab-button-active" : "tab-button"} disabled={item.disabled} aria-selected={value === item.value} aria-controls={`${id}-panel-${item.value}`} tabIndex={focused === item.value ? 0 : -1} onFocus={() => setFocused(item.value)} onKeyDown={(event) => { if (["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) { event.preventDefault(); move(event.key); } }} onClick={() => onChange(item.value)}>{item.label}</button>)}
    </div>
    {items.map((item) => <div key={item.value} id={`${id}-panel-${item.value}`} className="console-tab-panel" role="tabpanel" aria-labelledby={`${id}-tab-${item.value}`} tabIndex={0} hidden={item.value !== value}>{item.value === value ? children : null}</div>)}
  </div>;
}
