import { apiRequest } from "../../api/client";

export interface Expression {
  op: string;
  value?: number | null;
  alias?: string | null;
  left?: Expression | null;
  right?: Expression | null;
}
export interface VariableInput {
  unit: string;
  minimum: number | null;
  maximum: number | null;
}
export interface VariableContract {
  variableId: string;
  revision: number;
  key: string;
  label: string;
  origin: "OBSERVED" | "DERIVED";
  unit: string;
  languageVersion: string;
  inputs: Record<string, VariableInput>;
  expression: Expression;
  minimum: number | null;
  maximum: number | null;
}
export interface VariableDefinition {
  id: string;
  accountId: string;
  deviceType: string;
  key: string;
  label: string;
  origin: string;
  unit: string;
  version: number;
  archived: boolean;
  defaultRevision: number | null;
}
export interface VariableRevision {
  revision: number;
  state: string;
  digest: string;
  contract: VariableContract;
}
export interface VariableDetail {
  definition: VariableDefinition;
  revisions: VariableRevision[];
}
export type VariableDraft = Omit<
  VariableContract,
  "variableId" | "revision" | "languageVersion"
> & { expectedVersion: number | null };
export interface VariableBinding {
  dataSourceId: string;
  variableId: string;
  revision: number;
  version: number;
  inputPaths: Record<string, string>;
  enabled: boolean;
  inherited: boolean;
}
export interface VariableDescriptor {
  variableId: string;
  dataSourceId: string;
  sourceId: string;
  key: string;
  label: string;
  origin: string;
  unit: string;
  revision: number;
  generation: number;
  enabled: boolean;
  metricToken: string;
}
export interface VariablePoint {
  timestamp: string;
  value: number;
  revision: number;
  generation: number;
  count: number;
  minimum: number;
  maximum: number;
}
export interface VariableSeries {
  deviceId: string;
  variableId: string;
  dataSourceId: string;
  metric: string;
  unit: string;
  points: VariablePoint[];
  hasMore: boolean;
  nextCursor: string | null;
  aggregationMode: string;
}
export interface PreviewResult {
  value: number | null;
  unit: string;
  status: string;
  steps: { operation: string; value: number; unit: string }[];
}
export interface BindingStatus {
  desiredGeneration: number;
  bindings: VariableBinding[];
}
export const listVariables = (type: string, signal?: AbortSignal) =>
  apiRequest<VariableDefinition[]>(`/device-types/${type}/variables`, { signal });
export const variableDetail = (type: string, id: string) =>
  apiRequest<VariableDetail>(`/device-types/${type}/variables/${id}`);
export const saveVariable = (type: string, draft: VariableDraft, id?: string) =>
  apiRequest<VariableDetail>(`/device-types/${type}/variables${id ? `/${id}/revisions` : ""}`, {
    method: "POST",
    body: JSON.stringify(draft),
  });
export const publishVariable = (
  type: string,
  id: string,
  expectedVersion: number,
  revision: number,
) =>
  apiRequest<VariableDetail>(`/device-types/${type}/variables/${id}/publish`, {
    method: "POST",
    body: JSON.stringify({ expectedVersion, revision, makeDefault: false }),
  });
export const previewVariable = (
  deviceId: string,
  dataSourceId: string,
  contract: VariableContract,
  inputs: Record<string, number>,
) =>
  apiRequest<PreviewResult>("/telemetry/variables/preview", {
    method: "POST",
    body: JSON.stringify({ deviceId, dataSourceId, contract, inputs }),
  });
export const variableDescriptors = (deviceId: string, signal?: AbortSignal) =>
  apiRequest<VariableDescriptor[]>(`/telemetry/devices/${deviceId}/variables`, { signal });
export const bindingStatus = (device: string, source: string) =>
  apiRequest<BindingStatus>(`/devices/${device}/data-sources/${source}/variable-bindings/status`);
export const bindVariable = (
  device: string,
  source: string,
  variableId: string,
  revision: number,
  expectedVersion: number,
  inputPaths: Record<string, string>,
  enabled = true,
) =>
  apiRequest<VariableBinding[]>(`/devices/${device}/data-sources/${source}/variable-bindings`, {
    method: "PUT",
    body: JSON.stringify({ variableId, revision, expectedVersion, inputPaths, enabled }),
  });
export const archiveVariable = (type: string, id: string, expectedVersion: number) =>
  apiRequest<VariableDefinition>(`/device-types/${type}/variables/${id}/archive`, {
    method: "POST",
    body: JSON.stringify({ expectedVersion }),
  });
export const setVariableDefault = (
  type: string,
  id: string,
  expectedVersion: number,
  revision: number,
  sourceType: string,
  inputPaths: Record<string, string>,
) =>
  apiRequest<void>(`/device-types/${type}/variables/${id}/default-binding`, {
    method: "PUT",
    body: JSON.stringify({ expectedVersion, revision, sourceType, inputPaths }),
  });
export const reconcileVariableDefault = (type: string, id: string) =>
  apiRequest<{ deviceId: string; status: string; diagnostic: string | null }[]>(
    `/device-types/${type}/variables/${id}/inheritance/reconcile`,
    { method: "POST" },
  );
export function readVariable(
  device: string,
  variable: string,
  source: string,
  from: string,
  to: string,
  options: { bucketSeconds?: number; cursor?: string; limit?: number; signal?: AbortSignal } = {},
) {
  const query = new URLSearchParams({
    variableId: variable,
    sourceId: source,
    from,
    to,
    limit: String(options.limit ?? 1000),
  });
  if (options.bucketSeconds) query.set("bucketSeconds", String(options.bucketSeconds));
  if (options.cursor) query.set("cursor", options.cursor);
  return apiRequest<VariableSeries>(`/telemetry/devices/${device}/readings?${query}`, {
    signal: options.signal,
  });
}
export const latestVariable = (device: string, variable: string, source: string) =>
  apiRequest<VariableSeries>(
    `/telemetry/devices/${device}/latest?${new URLSearchParams({ variableId: variable, sourceId: source })}`,
  );
export const variableOutcomes = (device: string, source: string) =>
  apiRequest<
    {
      variableId: string;
      revision: number;
      generation: number;
      status: string;
      recordedAt: string;
    }[]
  >(
    `/telemetry/devices/${device}/variables/evaluations?${new URLSearchParams({ sourceId: source })}`,
  );
export interface RolloutResult {
  id: string;
  sourceId: string;
  status: string;
  diagnostic: string | null;
}
export const variableRollouts = (type: string, variable: string) =>
  apiRequest<RolloutResult[]>(`/device-types/${type}/variables/${variable}/rollouts`);
export const createVariableRollout = (
  type: string,
  variable: string,
  revision: number,
  targets: { deviceId: string; sourceId: string }[],
  inputPaths: Record<string, string>,
) =>
  apiRequest<RolloutResult[]>(`/device-types/${type}/variables/${variable}/rollouts`, {
    method: "POST",
    body: JSON.stringify({ revision, targets, inputPaths, enabled: true }),
  });
export const resumeVariableRollout = (type: string, variable: string, target: string) =>
  apiRequest<RolloutResult>(
    `/device-types/${type}/variables/${variable}/rollouts/${target}/resume`,
    { method: "POST" },
  );
