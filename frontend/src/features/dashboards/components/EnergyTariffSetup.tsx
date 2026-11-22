import { useEffect, useRef, useState } from "react";
import {
  archiveEnergyTariff,
  listEnergyTariffs,
  saveEnergyTariff,
  type EnergyTariff,
} from "../model/dashboardBusinessApi";
export function EnergyTariffSetup({
  accountId,
  workspaceId,
}: {
  accountId?: string;
  workspaceId?: string;
}) {
  const [history, setHistory] = useState<EnergyTariff[]>([]),
    [selected, setSelected] = useState("");
  const [currency, setCurrency] = useState("EUR"),
    [rate, setRate] = useState(""),
    [from, setFrom] = useState(new Date().toISOString().slice(0, 16)),
    [to, setTo] = useState(new Date(Date.now() + 365 * 86400000).toISOString().slice(0, 16));
  const [taxes, setTaxes] = useState(false),
    [reason, setReason] = useState(""),
    [reviewed, setReviewed] = useState(false),
    [error, setError] = useState<string | null>(null),
    [busy, setBusy] = useState(false);
  const lock = useRef(false);
  useEffect(() => {
    const c = new AbortController();
    setHistory([]);
    setSelected("");
    setReviewed(false);
    void listEnergyTariffs(accountId, workspaceId, c.signal)
      .then((value) => {
        if (!c.signal.aborted) setHistory(value);
      })
      .catch((e) => {
        if (!c.signal.aborted) setError(e instanceof Error ? e.message : "Tariffs unavailable");
      });
    return () => c.abort();
  }, [accountId, workspaceId]);
  const latest = history
    .filter((t) => t.tariffId === selected)
    .sort((a, b) => b.version - a.version)[0];
  async function archive() {
    if (lock.current || !reviewed || !selected) return;
    lock.current = true;
    setBusy(true);
    try {
      await archiveEnergyTariff(selected, accountId, workspaceId);
      setHistory(await listEnergyTariffs(accountId, workspaceId));
      setReviewed(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Archive failed");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  async function save() {
    if (lock.current || !reviewed) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      await saveEnergyTariff({
        tariffId: selected || null,
        version: latest?.version ?? 0,
        accountId,
        workspaceId,
        currency,
        timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
        ratePerKWh: rate,
        effectiveFrom: new Date(from + "Z").toISOString(),
        effectiveTo: new Date(to + "Z").toISOString(),
        taxesIncluded: taxes,
        reason,
        archived: false,
      });
      setHistory(await listEnergyTariffs(accountId, workspaceId));
      setReviewed(false);
      setReason("");
    } catch (e) {
      setError(e instanceof Error ? e.message : "Tariff save failed");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <details>
      <summary>Versioned energy tariff</summary>
      <fieldset disabled={busy}>
        <label>
          Tariff
          <select
            value={selected}
            onChange={(e) => {
              setSelected(e.target.value);
              setReviewed(false);
              const tariff = history
                .filter((t) => t.tariffId === e.target.value)
                .sort((a, b) => b.version - a.version)[0];
              if (tariff) {
                setRate(String(tariff.ratePerKWh));
                setCurrency(tariff.currency);
                setFrom(tariff.effectiveFrom.slice(0, 16));
                setTo(tariff.effectiveTo.slice(0, 16));
                setTaxes(tariff.taxesIncluded);
              }
            }}
          >
            <option value="">New tariff</option>
            {[...new Set(history.map((t) => t.tariffId))].map((id) => (
              <option key={id} value={id!}>
                {id}
              </option>
            ))}
          </select>
        </label>
        <label>
          Currency
          <input
            maxLength={3}
            value={currency}
            onChange={(e) => setCurrency(e.target.value.toUpperCase())}
          />
        </label>
        <label>
          Price per kWh
          <input inputMode="decimal" value={rate} onChange={(e) => setRate(e.target.value)} />
        </label>
        <label>
          Effective from (UTC)
          <input type="datetime-local" value={from} onChange={(e) => setFrom(e.target.value)} />
        </label>
        <label>
          Effective to (UTC)
          <input type="datetime-local" value={to} onChange={(e) => setTo(e.target.value)} />
        </label>
        <label>
          <input type="checkbox" checked={taxes} onChange={(e) => setTaxes(e.target.checked)} />
          Rate includes taxes
        </label>
        <label>
          Revision or correction reason
          <input maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        <p>
          Saved revisions affect estimates for their effective periods. Fixed fees and currency
          conversion are excluded.
        </p>
        <label>
          <input
            type="checkbox"
            checked={reviewed}
            onChange={(e) => setReviewed(e.target.checked)}
          />
          I reviewed this rate, period and tax policy
        </label>
        <button
          type="button"
          className="primary-button"
          disabled={!reviewed || !reason.trim() || !/^\d+(\.\d{1,8})?$/.test(rate)}
          onClick={() => void save()}
        >
          Save tariff revision
        </button>
        {latest ? (
          <button
            type="button"
            disabled={!reviewed || latest.archived}
            onClick={() => void archive()}
          >
            Archive tariff for future estimates
          </button>
        ) : null}
      </fieldset>
      {error ? <p role="alert">{error}</p> : null}
    </details>
  );
}
