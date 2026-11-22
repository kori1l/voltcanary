import { apiRequest } from "../api/client";
export interface FieldRule {
  path: string;
  key: string;
  label: string;
  type: "number" | "string" | "boolean";
  unit: string;
  store: boolean;
  display: boolean;
}
export interface TelemetryTemplate {
  id: string;
  name: string;
  deviceType: string | null;
  version: number;
  policy: { fields: FieldRule[] };
}
export function listTelemetryTemplates() {
  return apiRequest<TelemetryTemplate[]>("/devices/telemetry-templates");
}
export function saveTelemetryTemplate(
  template: Omit<TelemetryTemplate, "id" | "version"> & { id?: string; version?: number },
) {
  return apiRequest<TelemetryTemplate>(
    `/devices/telemetry-templates${template.id ? `/${template.id}` : ""}`,
    { method: template.id ? "PUT" : "POST", body: JSON.stringify(template) },
  );
}
export function assignTelemetryTemplate(
  templateId: string | null,
  targets: { deviceId: string; sourceId: string }[],
  deviceType?: string,
) {
  return apiRequest<{ assigned: number }>("/devices/telemetry-templates/assign", {
    method: "POST",
    body: JSON.stringify({ templateId, targets, deviceType }),
  });
}
