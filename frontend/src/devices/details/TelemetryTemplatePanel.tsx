import { useEffect, useState } from "react";
import { getDevice, type DataSource } from "../devicesApi";
import {
  assignTelemetryTemplate,
  listTelemetryTemplates,
  saveTelemetryTemplate,
  type FieldRule,
  type TelemetryTemplate,
} from "../telemetryTemplatesApi";
import { getTelemetryFields } from "../../telemetry/telemetryFieldsApi";

const hslFields: [string, string, FieldRule["type"], string][] = [
  ["latitude", "Latitude", "number", "deg"],
  ["longitude", "Longitude", "number", "deg"],
  ["speedKmh", "Speed", "number", "km/h"],
  ["headingDeg", "Heading", "number", "deg"],
  ["transport.routeId", "Route ID", "string", ""],
  ["transport.routeShortName", "Route", "string", ""],
  ["transport.direction", "Direction", "number", ""],
  ["transport.destination", "Destination", "string", ""],
  ["transport.nextStopId", "Next stop", "string", ""],
  ["transport.scheduleDeviationSeconds", "Schedule offset", "number", "s"],
  ["transport.doorsOpen", "Doors open", "boolean", ""],
  ["transport.accelerationMs2", "Acceleration", "number", "m/s²"],
  ["transport.scheduledStart", "Scheduled start", "string", ""],
  ["transport.locationSource", "Location source", "string", ""],
];
export function hslFieldRules(): FieldRule[] {
  return hslFields.map(([path, label, type, unit]) => ({
    path,
    key: path,
    label,
    type,
    unit,
    store: true,
    display: !["latitude", "longitude", "transport.routeId", "transport.locationSource"].includes(
      path,
    ),
  }));
}
export function TelemetryTemplatePanel({
  sources,
  onAssigned,
}: {
  sources: DataSource[];
  onAssigned: () => Promise<unknown>;
}) {
  const [templates, setTemplates] = useState<TelemetryTemplate[]>([]),
    [sourceId, setSourceId] = useState(""),
    [draft, setDraft] = useState<Partial<TelemetryTemplate>>({ name: "", policy: { fields: [] } });
  const [busy, setBusy] = useState(false),
    [message, setMessage] = useState(""),
    [deviceType, setDeviceType] = useState("");
  const source = sources.find((item) => item.id === sourceId) ?? sources[0];
  useEffect(() => {
    let active = true;
    void listTelemetryTemplates()
      .then((items) => {
        if (active) setTemplates(items);
      })
      .catch((error) => {
        if (active) setMessage(String(error));
      });
    return () => {
      active = false;
    };
  }, []);
  useEffect(() => {
    let active = true;
    if (source)
      void getDevice(source.deviceId)
        .then((device) => {
          if (active) setDeviceType(device.type);
        })
        .catch((error) => {
          if (active) setMessage(String(error));
        });
    return () => {
      active = false;
    };
  }, [source?.deviceId]);
  const fields = draft.policy?.fields ?? [];
  const assigned = String(source?.mapping?.telemetryTemplateId ?? "");
  async function act(action: () => Promise<unknown>) {
    setBusy(true);
    setMessage("");
    try {
      await action();
    } catch (error) {
      setMessage(error instanceof Error ? error.message : String(error));
    } finally {
      setBusy(false);
    }
  }
  function change(index: number, patch: Partial<FieldRule>) {
    setDraft((prior) => ({
      ...prior,
      policy: {
        fields: fields.map((field, i) => {
          if (
            patch.store != null &&
            ["latitude", "longitude"].includes(fields[index].key) &&
            ["latitude", "longitude"].includes(field.key)
          )
            return { ...field, store: patch.store };
          return i === index ? { ...field, ...patch } : field;
        }),
      },
    }));
  }
  return (
    <section className="panel telemetry-template-panel">
      <h2>Telemetry field templates</h2>
      <p>
        Choose fields to store in future readings. Templates are shared across assigned sources;
        existing history is retained.
      </p>
      {message ? <p role="status">{message}</p> : null}
      <div className="form-grid">
        <label>
          Source
          <select value={source?.id ?? ""} onChange={(event) => setSourceId(event.target.value)}>
            {sources.map((item) => (
              <option key={item.id} value={item.id}>
                {item.externalId}
              </option>
            ))}
          </select>
        </label>
        <label>
          Assigned template
          <select
            disabled={!source || busy}
            value={assigned}
            onChange={(event) =>
              void act(async () => {
                await assignTelemetryTemplate(event.target.value || null, [
                  { deviceId: source!.deviceId, sourceId: source!.id },
                ]);
                await onAssigned();
                setMessage("Source assignment saved. New readings use it after propagation.");
              })
            }
          >
            <option value="">Legacy collection (no template)</option>
            {templates.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name} · v{item.version}
              </option>
            ))}
          </select>
        </label>
      </div>
      <div className="action-row">
        <label>
          Edit template
          <select
            value={draft.id ?? ""}
            onChange={(event) =>
              setDraft(
                templates.find((item) => item.id === event.target.value) ?? {
                  name: "",
                  policy: { fields: [] },
                },
              )
            }
          >
            <option value="">New template</option>
            {templates.map((item) => (
              <option key={item.id} value={item.id}>
                {item.name}
              </option>
            ))}
          </select>
        </label>
        <button
          className="ghost-button"
          disabled={busy}
          onClick={() =>
            setDraft({
              name: "HSL transport",
              deviceType: deviceType || null,
              policy: { fields: hslFieldRules() },
            })
          }
        >
          Use HSL fields
        </button>
        <button
          className="ghost-button"
          disabled={!source || busy}
          onClick={() =>
            void act(async () => {
              const observed = await getTelemetryFields(source!.deviceId, source!.externalId);
              setDraft({
                name: "Device readings",
                deviceType: deviceType || null,
                policy: {
                  fields: observed.map((field) => ({
                    path: field.key,
                    key: field.key,
                    label: field.label,
                    type: field.type,
                    unit: field.unit,
                    store: true,
                    display: true,
                  })),
                },
              });
            })
          }
        >
          Use observed fields
        </button>
      </div>
      <label>
        Template name
        <input
          value={draft.name ?? ""}
          maxLength={80}
          onChange={(event) => setDraft({ ...draft, name: event.target.value })}
        />
      </label>
      <div className="telemetry-field-table">
        <table>
          <thead>
            <tr>
              <th>JSON field path</th>
              <th>Metric key</th>
              <th>Label</th>
              <th>Type</th>
              <th>Unit</th>
              <th>Store</th>
              <th>Show by default</th>
              <th />
            </tr>
          </thead>
          <tbody>
            {fields.map((field, index) => (
              <tr key={index}>
                <td>
                  <input
                    aria-label={`Field path ${index + 1}`}
                    value={field.path}
                    onChange={(event) => change(index, { path: event.target.value })}
                  />
                </td>
                <td>
                  <input
                    aria-label={`Metric key ${index + 1}`}
                    value={field.key}
                    onChange={(event) => change(index, { key: event.target.value })}
                  />
                </td>
                <td>
                  <input
                    aria-label={`Field label ${index + 1}`}
                    value={field.label}
                    onChange={(event) => change(index, { label: event.target.value })}
                  />
                </td>
                <td>
                  <select
                    aria-label={`Field type ${index + 1}`}
                    value={field.type}
                    onChange={(event) =>
                      change(index, { type: event.target.value as FieldRule["type"] })
                    }
                  >
                    <option>number</option>
                    <option>string</option>
                    <option>boolean</option>
                  </select>
                </td>
                <td>
                  <input
                    aria-label={`Field unit ${index + 1}`}
                    value={field.unit}
                    onChange={(event) => change(index, { unit: event.target.value })}
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`Store ${field.label || index + 1}`}
                    checked={field.store}
                    onChange={(event) => change(index, { store: event.target.checked })}
                  />
                </td>
                <td>
                  <input
                    type="checkbox"
                    aria-label={`Display ${field.label || index + 1}`}
                    checked={field.display}
                    disabled={!field.store}
                    onChange={(event) => change(index, { display: event.target.checked })}
                  />
                </td>
                <td>
                  <button
                    className="icon-button"
                    aria-label={`Remove field ${index + 1}`}
                    onClick={() =>
                      setDraft({
                        ...draft,
                        policy: { fields: fields.filter((_, i) => i !== index) },
                      })
                    }
                  >
                    ×
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <small>
        Paths are relative to incoming metrics, e.g. transport.destination. Only selected fields are
        retained. Latitude and longitude must be stored together.
      </small>
      <div className="action-row">
        <button
          className="ghost-button"
          disabled={fields.length >= 32 || busy}
          onClick={() =>
            setDraft({
              ...draft,
              policy: {
                fields: [
                  ...fields,
                  {
                    path: "",
                    key: "",
                    label: "",
                    type: "number",
                    unit: "",
                    store: true,
                    display: true,
                  },
                ],
              },
            })
          }
        >
          Add field
        </button>
        <button
          className="primary-button"
          disabled={busy || !draft.name?.trim()}
          onClick={() =>
            void act(async () => {
              const saved = await saveTelemetryTemplate({
                id: draft.id,
                name: draft.name!,
                deviceType: draft.deviceType ?? (deviceType || null),
                version: draft.version,
                policy: { fields },
              });
              setTemplates(await listTelemetryTemplates());
              setDraft(saved);
              await onAssigned();
              setMessage("Template saved; assigned sources updated.");
            })
          }
        >
          Save template
        </button>
        <button
          className="ghost-button"
          disabled={busy || !draft.id || !deviceType}
          onClick={() =>
            void act(async () => {
              const result = await assignTelemetryTemplate(draft.id!, [], deviceType);
              await onAssigned();
              setMessage(`Assigned to ${result.assigned} existing ${deviceType} sources.`);
            })
          }
        >
          Apply to all {deviceType || "same-type"} sources
        </button>
      </div>
    </section>
  );
}
