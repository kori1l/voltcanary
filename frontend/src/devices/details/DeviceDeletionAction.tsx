import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Dialog } from "../../components/Dialog";
import { requestDeviceDeletion } from "../devicesApi";

export function DeviceDeletionAction({
  deviceId,
  name,
  disabled = false,
}: {
  deviceId: string;
  name: string;
  disabled?: boolean;
}) {
  const navigate = useNavigate();
  const [open, setOpen] = useState(false),
    [deleteHistory, setDeleteHistory] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null);
  async function remove() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await requestDeviceDeletion(deviceId, deleteHistory);
      setOpen(false);
      navigate("/devices/deletions");
    } catch (err) {
      setError(
        err instanceof Error ? err.message : "Не удалось удалить устройство. Повторите попытку.",
      );
    } finally {
      setBusy(false);
    }
  }
  return (
    <>
      <button
        type="button"
        className="danger-button"
        disabled={disabled || busy}
        onClick={() => {
          setOpen(true);
          setError(null);
          setDeleteHistory(false);
        }}
      >
        Удалить устройство
      </button>
      {open ? (
        <Dialog
          title="Удалить устройство"
          busy={busy}
          onClose={() => setOpen(false)}
          footer={
            <>
              <button
                type="button"
                className="ghost-button"
                disabled={busy}
                onClick={() => setOpen(false)}
              >
                Отмена
              </button>
              <button
                type="button"
                className="danger-button"
                disabled={busy}
                onClick={() => void remove()}
              >
                {busy
                  ? "Удаление…"
                  : deleteHistory
                    ? "Удалить устройство и историю"
                    : "Удалить устройство, сохранить историю"}
              </button>
            </>
          }
        >
          <p>
            <strong>{name}</strong>
          </p>
          <p>
            Устройство будет удалено из списка и карты. Сбор новых данных прекратится. Восстановить
            устройство будет нельзя.
          </p>
          <fieldset disabled={busy}>
            <legend>Что сделать с историей?</legend>
            <label>
              <input
                type="radio"
                name="device-history-policy"
                checked={!deleteHistory}
                onChange={() => setDeleteHistory(false)}
              />{" "}
              Сохранить историю удалённого устройства
            </label>
            <label>
              <input
                type="radio"
                name="device-history-policy"
                checked={deleteHistory}
                onChange={() => setDeleteHistory(true)}
              />{" "}
              Удалить историю вместе с устройством
            </label>
          </fieldset>
          {deleteHistory ? (
            <p className="error-message">
              История измерений и событий будет удалена без возможности восстановления.
            </p>
          ) : null}
          {error ? (
            <p role="alert" className="error-message">
              {error}
            </p>
          ) : null}
        </Dialog>
      ) : null}
    </>
  );
}
