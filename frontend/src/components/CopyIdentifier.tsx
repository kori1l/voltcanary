import { useState } from "react";
export function CopyIdentifier({ value }: { value: string }) {
  const [message, setMessage] = useState("");
  async function copy() { try { await navigator.clipboard.writeText(value); setMessage("ID copied."); } catch { setMessage("Copy failed. Select the identifier and copy it manually."); } }
  return <><code tabIndex={0}>{value}</code><button type="button" className="ghost-button" onClick={() => void copy()}>Copy ID</button><span role="status">{message}</span></>;
}
