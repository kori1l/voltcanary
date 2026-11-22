import { useEffect, useRef, useState } from "react";
import { listDataSources, type DataSource } from "../../../devices/devicesApi";
import {
  listWorkspaceSources,
  listOperationalFacts,
  listOperatingSchedules,
  saveOperationalFact,
  saveOperatingSchedule,
  type OperationalFact,
  type OperatingSchedule,
} from "../model/dashboardBusinessApi";
export function OperationalFactsSetup({
  deviceId,
  accountId,
  workspaceId,
}: {
  deviceId: string;
  accountId?: string;
  workspaceId?: string;
}) {
  const [sources, setSources] = useState<DataSource[]>([]),
    [source, setSource] = useState(""),
    [sourceEvent, setSourceEvent] = useState("");
  const [kind, setKind] = useState("CYCLE"),
    [completeness, setCompleteness] = useState("COMPLETED"),
    [start, setStart] = useState(""),
    [end, setEnd] = useState("");
  const [reason, setReason] = useState(""),
    [reviewed, setReviewed] = useState(false),
    [busy, setBusy] = useState(false),
    [error, setError] = useState<string | null>(null),
    [message, setMessage] = useState("");
  const [day, setDay] = useState(1),
    [slotFrom, setSlotFrom] = useState("08:00"),
    [slotTo, setSlotTo] = useState("18:00"),
    [holidays, setHolidays] = useState("");
  const [scheduleFrom, setScheduleFrom] = useState(new Date().toISOString().slice(0, 16)),
    [scheduleTo, setScheduleTo] = useState(
      new Date(Date.now() + 365 * 86400000).toISOString().slice(0, 16),
    );
  const [factRevision, setFactRevision] = useState<OperationalFact | null>(null),
    [scheduleRevision, setScheduleRevision] = useState<OperatingSchedule | null>(null),
    [facts, setFacts] = useState<OperationalFact[]>([]),
    [schedules, setSchedules] = useState<OperatingSchedule[]>([]);
  const lock = useRef(false);
  const contextVersion = useRef(0);
  const [timezone, setTimezone] = useState(Intl.DateTimeFormat().resolvedOptions().timeZone);
  useEffect(() => {
    contextVersion.current++;
    setFacts([]);
    setSchedules([]);
    setFactRevision(null);
    setScheduleRevision(null);
    setReviewed(false);
    setReason("");
    setSourceEvent("");
    setStart("");
    setEnd("");
    setMessage("");
    setError(null);
    setTimezone(Intl.DateTimeFormat().resolvedOptions().timeZone);
    return () => {
      contextVersion.current++;
    };
  }, [deviceId, accountId, workspaceId]);
  async function loadHistory() {
    const version = contextVersion.current,
      now = new Date().toISOString();
    const [history, scheduleHistory] = await Promise.all([
      listOperationalFacts(
        deviceId,
        new Date(Date.now() - 30 * 86400000).toISOString(),
        now,
        accountId,
        workspaceId,
      ),
      listOperatingSchedules(deviceId, undefined, accountId, workspaceId),
    ]);
    if (version === contextVersion.current) {
      setFacts(history);
      setSchedules(scheduleHistory);
    }
  }

  useEffect(() => {
    let active = true;
    setSources([]);
    setSource("");
    void (
      workspaceId && accountId
        ? listWorkspaceSources(workspaceId, accountId, deviceId)
        : listDataSources(deviceId)
    )
      .then((s) => {
        if (active) setSources(s);
      })
      .catch((e) => {
        if (active) setError(e instanceof Error ? e.message : "Sources unavailable");
      });
    return () => {
      active = false;
    };
  }, [deviceId, accountId, workspaceId]);
  async function save(schedule = false) {
    if (lock.current || !reviewed) return;
    lock.current = true;
    setBusy(true);
    setError(null);
    try {
      if (schedule)
        await saveOperatingSchedule(
          {
            scheduleId: scheduleRevision?.scheduleId ?? null,
            version: scheduleRevision?.version ?? 0,
            deviceId,
            timezone,
            effectiveFrom: new Date(scheduleFrom + "Z").toISOString(),
            effectiveTo: new Date(scheduleTo + "Z").toISOString(),
            weekSlots: [{ dayOfWeek: day, from: slotFrom, to: slotTo }],
            holidays: holidays
              .split(",")
              .map((s) => s.trim())
              .filter(Boolean),
            reason,
          },
          accountId,
          workspaceId,
        );
      else
        await saveOperationalFact(
          {
            eventId: factRevision?.eventId ?? null,
            version: factRevision?.version ?? 0,
            deviceId,
            sourceId: source,
            sourceEventId: sourceEvent,
            kind,
            completeness,
            startAt: new Date(start + "Z").toISOString(),
            endAt: end ? new Date(end + "Z").toISOString() : null,
            reason,
            evidence: null,
          },
          accountId,
          workspaceId,
        );
      setMessage(schedule ? "Schedule revision saved" : "Confirmed fact saved");
      setReviewed(false);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Operational fact save failed");
    } finally {
      lock.current = false;
      setBusy(false);
    }
  }
  return (
    <details>
      <summary>Confirmed operations and schedule</summary>
      <fieldset disabled={busy}>
        <button
          type="button"
          onClick={() =>
            void loadHistory().catch((e) =>
              setError(e instanceof Error ? e.message : "History unavailable"),
            )
          }
        >
          Load recent facts and schedule revisions
        </button>
        <label>
          Fact revision
          <select
            value={factRevision?.eventId ?? ""}
            onChange={(e) => {
              const f = facts.find((f) => f.eventId === e.target.value) ?? null;
              setFactRevision(f);
              setReviewed(false);
              setReason("");
              if (f) {
                setSource(f.sourceId);
                setSourceEvent(f.sourceEventId);
                setKind(f.kind);
                setCompleteness(f.completeness);
                setStart(f.startAt.slice(0, 16));
                setEnd(f.endAt?.slice(0, 16) ?? "");
              }
            }}
          >
            <option value="">New fact</option>
            {facts.map((f) => (
              <option key={f.eventId} value={f.eventId!}>
                {f.kind} {f.startAt} v{f.version}
              </option>
            ))}
          </select>
        </label>
        <p>
          Record confirmed source facts. Power peaks and telemetry gaps do not create cycles or
          downtime.
        </p>
        <label>
          Source
          <select value={source} onChange={(e) => setSource(e.target.value)}>
            <option value="">Registered source</option>
            {sources.map((s) => (
              <option key={s.id} value={s.id}>
                {s.externalId}
              </option>
            ))}
          </select>
        </label>
        <label>
          Source event identity
          <input
            maxLength={160}
            value={sourceEvent}
            onChange={(e) => setSourceEvent(e.target.value)}
          />
        </label>
        <label>
          Fact
          <select value={kind} onChange={(e) => setKind(e.target.value)}>
            {["CYCLE", "DOWNTIME", "PLANNED_DOWNTIME", "OBSERVATION"].map((k) => (
              <option key={k}>{k}</option>
            ))}
          </select>
        </label>
        <label>
          Completeness
          <select value={completeness} onChange={(e) => setCompleteness(e.target.value)}>
            {["COMPLETED", "ABORTED", "INCOMPLETE"].map((k) => (
              <option key={k}>{k}</option>
            ))}
          </select>
        </label>
        <label>
          Starts (UTC)
          <input type="datetime-local" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        <label>
          Ends (UTC, leave blank if incomplete)
          <input type="datetime-local" value={end} onChange={(e) => setEnd(e.target.value)} />
        </label>
        <label>
          Reason
          <input maxLength={500} value={reason} onChange={(e) => setReason(e.target.value)} />
        </label>
        <label>
          <input
            type="checkbox"
            checked={reviewed}
            onChange={(e) => setReviewed(e.target.checked)}
          />
          I reviewed the confirmed fact or schedule
        </label>
        <button
          type="button"
          className="primary-button"
          disabled={
            !reviewed ||
            !source ||
            !sourceEvent ||
            !start ||
            !reason.trim() ||
            (completeness === "COMPLETED" && !end)
          }
          onClick={() => void save()}
        >
          Save confirmed fact
        </button>
        <details>
          <summary>Operating schedule</summary>
          <label>
            Schedule revision
            <select
              value={scheduleRevision?.scheduleId ?? ""}
              onChange={(e) => {
                const r =
                  schedules
                    .filter((s) => s.scheduleId === e.target.value)
                    .sort((a, b) => b.version - a.version)[0] ?? null;
                setScheduleRevision(r);
                setReviewed(false);
                setReason("");
                if (r) {
                  setTimezone(r.timezone);
                  setScheduleFrom(r.effectiveFrom.slice(0, 16));
                  setScheduleTo(r.effectiveTo.slice(0, 16));
                  setHolidays(r.holidays.join(","));
                  if (r.weekSlots[0]) {
                    setDay(r.weekSlots[0].dayOfWeek);
                    setSlotFrom(r.weekSlots[0].from);
                    setSlotTo(r.weekSlots[0].to);
                  }
                }
              }}
            >
              <option value="">New schedule</option>
              {[...new Set(schedules.map((s) => s.scheduleId))].map((id) => (
                <option key={id} value={id!}>
                  {id}
                </option>
              ))}
            </select>
          </label>
          <label>
            Weekday
            <select value={day} onChange={(e) => setDay(Number(e.target.value))}>
              {["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"].map(
                (d, i) => (
                  <option key={d} value={i + 1}>
                    {d}
                  </option>
                ),
              )}
            </select>
          </label>
          <label>
            Local start
            <input type="time" value={slotFrom} onChange={(e) => setSlotFrom(e.target.value)} />
          </label>
          <label>
            Local end
            <input type="time" value={slotTo} onChange={(e) => setSlotTo(e.target.value)} />
          </label>
          <label>
            Effective from (UTC)
            <input
              type="datetime-local"
              value={scheduleFrom}
              onChange={(e) => setScheduleFrom(e.target.value)}
            />
          </label>
          <label>
            Effective to (UTC)
            <input
              type="datetime-local"
              value={scheduleTo}
              onChange={(e) => setScheduleTo(e.target.value)}
            />
          </label>
          <label>
            Holidays (YYYY-MM-DD, comma separated)
            <input value={holidays} onChange={(e) => setHolidays(e.target.value)} />
          </label>
          <label>
            Schedule timezone
            <input
              value={timezone}
              onChange={(e) => {
                setTimezone(e.target.value);
                setReviewed(false);
              }}
            />
          </label>
          <p>DST gaps shift forward; overlap uses the earlier offset.</p>
          <button
            type="button"
            disabled={!reviewed || !reason.trim()}
            onClick={() => void save(true)}
          >
            Save schedule
          </button>
        </details>
      </fieldset>
      {message ? <p role="status">{message}</p> : null}
      {error ? <p role="alert">{error}</p> : null}
    </details>
  );
}
