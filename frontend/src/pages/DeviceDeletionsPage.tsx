import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { listDeviceDeletions, type DeviceDeletionReceipt } from "../devices/devicesApi";
import { getTelemetryMetrics, getTelemetryReadings, type TelemetryChart, type TelemetryMetricMetadata } from "../telemetry/telemetryApi";

export function DeviceDeletionsPage() {
  const [loaded, setLoaded] = useState(false);
  const [rows, setRows] = useState<DeviceDeletionReceipt[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<DeviceDeletionReceipt | null>(null);
  const [from, setFrom] = useState(() => new Date(Date.now() - 86400000).toISOString().slice(0,16));
  const [to, setTo] = useState(() => new Date().toISOString().slice(0,16));
  const [range, setRange] = useState({from: new Date(Date.now() - 86400000).toISOString(), to: new Date().toISOString()});
  const [metrics, setMetrics] = useState<TelemetryMetricMetadata[]>([]);
  const [metric, setMetric] = useState("");
  const [chart, setChart] = useState<TelemetryChart | null>(null);
  const [loading, setLoading] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    const load = () => listDeviceDeletions(controller.signal).then(data => { setRows(data); setLoaded(true); }).catch(e => { if (!controller.signal.aborted) setError(e.message); });
    void load(); const timer = window.setInterval(() => void load(), 10000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, []);
  useEffect(() => {
    setMetrics([]); setMetric(""); setChart(null); setError(null);
    if (!selected) return;
    const controller = new AbortController(); setLoading(true);
    void getTelemetryMetrics(selected.deviceId, range.from, range.to, controller.signal)
      .then(data => { setMetrics(data); setMetric(data[0]?.name ?? ""); })
      .catch(e => { if (!controller.signal.aborted) setError(e.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [selected, range]);
  useEffect(() => {
    setChart(null); if (!selected || !metric) return;
    const controller = new AbortController(); setLoading(true);
    void getTelemetryReadings(selected.deviceId, metric, range.from, range.to, controller.signal).then(setChart)
      .catch(e => { if (!controller.signal.aborted) setError(e.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [selected, metric, range]);
  return <section>
    <header className="page-header"><Link to="/devices">Устройства</Link><h1>Удалённые устройства</h1>
      <p>Удаление необратимо. Очистка продолжится автоматически при восстановлении недоступных сервисов.</p></header>
    {error ? <p role="alert" className="error-message">{error}</p> : null}
    <div className="panel"><table><thead><tr><th>Устройство</th><th>История</th><th>Состояние</th><th/></tr></thead>
      <tbody>{rows.map(row => <tr key={row.deviceId}><td>{row.name}<br/><small>{new Date(row.createdAt).toLocaleString()}</small></td>
        <td>{row.deleteHistory ? row.status === "COMPLETED" ? "Удалена" : "Удаляется" : "Сохранена"}</td><td>{row.status === "COMPLETED" ? "Удалено" : "Очистка выполняется"}
          {row.lastError ? <p>{row.lastError.includes("dependent ML") ? "Очистка ожидает удаления связанных ML-датасетов или снимков признаков." : "Ожидается доступность сервиса очистки. Повтор выполняется автоматически."}</p> : null}</td>
        <td>{!row.deleteHistory ? <button type="button" className="ghost-button" onClick={() => setSelected(row)}>Посмотреть историю</button> : null}</td></tr>)}</tbody></table>
      {!loaded && !error ? <p role="status">Загрузка удалённых устройств…</p> : loaded && !rows.length ? <p>Удалённых устройств пока нет.</p> : null}</div>
    {selected ? <section className="panel"><h2>История: {selected.name}</h2>
      <form onSubmit={event => { event.preventDefault(); setRange({from: new Date(from + "Z").toISOString(), to: new Date(to + "Z").toISOString()}); }}>
        <label>С (UTC)<input required type="datetime-local" value={from} onChange={event => setFrom(event.target.value)}/></label>
        <label>По (UTC)<input required type="datetime-local" min={from} value={to} onChange={event => setTo(event.target.value)}/></label>
        <button type="submit" className="ghost-button">Показать период</button></form>
      <label>Показатель<select value={metric} onChange={event => setMetric(event.target.value)}>{metrics.map(item => <option key={item.name} value={item.name}>{item.displayName || item.name}</option>)}</select></label>
      {loading ? <p role="status">Загрузка истории…</p> : !chart?.points.length ? <p>Нет измерений за выбранный период.</p> :
        <table><thead><tr><th>Время</th><th>Значение {chart.unit}</th></tr></thead><tbody>{chart.points.map((point,index) => <tr key={index}><td>{new Date(point.timestamp).toLocaleString()}</td><td>{point.value}</td></tr>)}</tbody></table>}
    </section> : null}
  </section>;
}
