import type { SectionResource } from "./useSectionResource";
import { EvidenceAge } from "./EvidenceAge";

export function ResourceErrors({ sections }: { sections: { label: string; resource: SectionResource<unknown>; retry: () => Promise<unknown> }[] }) {
  const groups = new Map<string, typeof sections>();
  sections.forEach((section) => { if (section.resource.error) groups.set(section.resource.error, [...(groups.get(section.resource.error) ?? []), section]); });
  return <>{[...groups].map(([error, items]) => <div className="error-message" key={error}>
    <p role="alert">{items.map((item) => `${item.label}: ${item.resource.status === "stale" ? "stale evidence" : "unavailable"}`).join(" · ")}. {error}</p>
    {items.filter((item) => item.resource.receivedAt).map((item) => <p key={item.label}>{item.label} last refreshed: <EvidenceAge value={item.resource.receivedAt} /></p>)}
    {items.map((item) => <button className="ghost-button" type="button" key={item.label} disabled={item.resource.refreshing} onClick={() => void item.retry()}>Retry {item.label}</button>)}
  </div>)}</>;
}
