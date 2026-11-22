import { useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { sessionIdentityRevision } from "../../api/client";
import { getAssistantProfile } from "../../aiAgent/aiAgentApi";
import { describeSourceBinding } from "../devicesApi";

export function SourceMappingLauncher({
  sourceId,
  deviceId,
}: {
  sourceId: string;
  deviceId: string;
}) {
  const navigate = useNavigate();
  const pending = useRef(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function open() {
    if (pending.current) return;
    const identity = sessionIdentityRevision();
    pending.current = true;
    setBusy(true);
    setError(null);
    try {
      const profile = await getAssistantProfile();
      if (identity !== sessionIdentityRevision()) return;
      if (profile.sourceMappingEnabled !== true) {
        setError("Подготовка маппинга в ассистенте отключена.");
        return;
      }
      const source = await describeSourceBinding(sourceId);
      if (identity !== sessionIdentityRevision()) return;
      if (
        source.id !== sourceId ||
        source.deviceId !== deviceId ||
        source.sourceActive !== true ||
        source.schemaRevision !== source.currentSchemaRevision ||
        !Number.isSafeInteger(source.schemaRevision) ||
        source.schemaRevision < 1
      ) {
        setError("Актуальная схема источника недоступна.");
        return;
      }
      const query = new URLSearchParams({
        workflow: "source-mapping",
        sourceBinding: `${source.id}:${source.schemaRevision}`,
      });
      navigate(`/ai-agent?${query}`);
    } catch {
      if (identity === sessionIdentityRevision())
        setError("Не удалось открыть подготовку маппинга.");
    } finally {
      pending.current = false;
      setBusy(false);
    }
  }

  return (
    <>
      <button className="ghost-button" type="button" disabled={busy} onClick={() => void open()}>
        {busy ? "Проверяем схему…" : "Подготовить маппинг"}
      </button>
      {error ? <span role="alert">{error}</span> : null}
    </>
  );
}
