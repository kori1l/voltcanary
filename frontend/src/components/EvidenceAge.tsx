import { useEvidenceClock } from "./useEvidenceClock";
import { TimeDisplay } from "./TimeDisplay";

export function EvidenceAge({ value }: { value: string | null | undefined }) {
  const now = useEvidenceClock();
  const measured = value ? Date.parse(value) : NaN;
  const seconds = Math.max(0, Math.floor((now - measured) / 1000));
  const age = seconds < 60 ? `${seconds}s` : seconds < 3600 ? `${Math.floor(seconds / 60)}m` : seconds < 86400 ? `${Math.floor(seconds / 3600)}h` : `${Math.floor(seconds / 86400)}d`;
  return <span><TimeDisplay value={value} />{Number.isFinite(measured) ? ` · ${age} ago` : ""}</span>;
}
