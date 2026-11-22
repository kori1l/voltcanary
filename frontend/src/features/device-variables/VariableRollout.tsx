import { useState } from "react";
import { listDataSources, listDevices } from "../../devices/devicesApi";
import * as api from "./variablesApi";

interface Target {
  deviceId: string;
  sourceId: string;
  label: string;
}
export function VariableRollout({
  type,
  variableId,
  revision,
  inputPaths,
}: {
  type: string;
  variableId: string;
  revision: number;
  inputPaths: Record<string, string>;
}) {
  const [open, setOpen] = useState(false),
    [busy, setBusy] = useState(false);
  const [targets, setTargets] = useState<Target[]>([]),
    [selected, setSelected] = useState<string[]>([]);
  const [results, setResults] = useState<api.RolloutResult[]>([]),
    [message, setMessage] = useState("");
  async function act(action: () => Promise<void>) {
    setBusy(true);
    setMessage("");
    try {
      await action();
    } catch (error) {
      setMessage(String(error));
    } finally {
      setBusy(false);
    }
  }
  return (
    <section aria-label="Variable rollout">
      <button
        className="ghost-button"
        type="button"
        disabled={busy}
        onClick={() =>
          void act(async () => {
            if (open) {
              setOpen(false);
              return;
            }
            const devices = await listDevices({ type, status: "ACTIVE", limit: 50 });
            const groups = await Promise.all(
              devices.map(async (device) =>
                (await listDataSources(device.id)).map((source) => ({
                  deviceId: device.id,
                  sourceId: source.id,
                  label: `${device.name} · ${source.externalId}`,
                })),
              ),
            );
            setTargets(groups.flat());
            setSelected([]);
            setResults(await api.variableRollouts(type, variableId));
            setOpen(true);
          })
        }
      >
        {open ? "Close rollout" : "Roll out to selected sources"}
      </button>
      {open ? (
        <fieldset disabled={busy}>
          <legend>Published revision {revision} · select up to 50 sources</legend>
          <p>
            Uses the current input mapping:{" "}
            {Object.entries(inputPaths)
              .map(([alias, path]) => `${alias} → ${path}`)
              .join(", ")}
            . Each source is validated independently. Existing bindings change only for selected
            targets.
          </p>
          {targets.length ? (
            targets.map((target) => (
              <label key={target.sourceId} className="checkbox-label">
                <input
                  type="checkbox"
                  checked={selected.includes(target.sourceId)}
                  disabled={!selected.includes(target.sourceId) && selected.length >= 50}
                  onChange={(event) =>
                    setSelected((prior) =>
                      event.target.checked
                        ? [...prior, target.sourceId]
                        : prior.filter((id) => id !== target.sourceId),
                    )
                  }
                />
                {target.label}
              </label>
            ))
          ) : (
            <p>No sources in the first 50 active devices of this type.</p>
          )}
          <div className="action-row">
            <button
              className="ghost-button"
              type="button"
              disabled={!selected.length}
              onClick={() =>
                void act(async () => {
                  const chosen = targets
                    .filter((target) => selected.includes(target.sourceId))
                    .map(({ deviceId, sourceId }) => ({ deviceId, sourceId }));
                  await api.createVariableRollout(type, variableId, revision, chosen, inputPaths);
                  setResults(await api.variableRollouts(type, variableId));
                  setSelected([]);
                  setMessage("Rollout queued. Refresh to inspect per-source outcomes.");
                })
              }
            >
              Apply to {selected.length} sources
            </button>
            <button
              className="ghost-button"
              type="button"
              onClick={() =>
                void act(async () => setResults(await api.variableRollouts(type, variableId)))
              }
            >
              Refresh progress
            </button>
          </div>
          <ul>
            {results.map((result) => (
              <li key={result.id}>
                {targets.find((target) => target.sourceId === result.sourceId)?.label ??
                  result.sourceId}
                : {result.status}
                {result.diagnostic ? ` · ${result.diagnostic}` : ""}
                {result.status === "FAILED" || result.status === "PENDING" ? (
                  <button
                    className="ghost-button"
                    type="button"
                    onClick={() =>
                      void act(async () => {
                        await api.resumeVariableRollout(type, variableId, result.id);
                        setResults(await api.variableRollouts(type, variableId));
                      })
                    }
                  >
                    Resume target
                  </button>
                ) : null}
              </li>
            ))}
          </ul>
        </fieldset>
      ) : null}
      {message ? <p role="status">{message}</p> : null}
    </section>
  );
}
