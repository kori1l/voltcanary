import { clearScopedDraft, draftScope, readScopedDraft, saveScopedDraft, sessionUserId } from "../components/scopedDrafts";
import type { DeviceAccount } from "./devicesApi";
import type { DeviceSetupStage, DeviceSourceMode } from "./createDeviceFlow";
export interface DeviceSetupCheckpoint { deviceId?: string; sourceId?: string; locationId?: string; configurationId?: string; runId?: string; pendingStage?: DeviceSetupStage; name: string; type: string; mode: DeviceSourceMode; externalId: string; profile: string; preset: string; startImmediately: boolean }
const fields = ["deviceId", "sourceId", "locationId", "configurationId", "runId", "pendingStage", "name", "type", "mode", "externalId", "profile", "preset", "startImmediately"] as const;
function scope(account: DeviceAccount | null) { return account && account.userId === sessionUserId() ? draftScope(account.id, "device-setup", "v1") : null; }
export function writeDeviceSetupCheckpoint(account: DeviceAccount | null, checkpoint: DeviceSetupCheckpoint) { return saveScopedDraft(scope(account), checkpoint, fields); }
export function clearDeviceSetupCheckpoint(account: DeviceAccount | null) { clearScopedDraft(scope(account)); }
export function readDeviceSetupCheckpoint(account: DeviceAccount | null) {
  return readScopedDraft(scope(account), (value): DeviceSetupCheckpoint | null => {
    if (!["API", "MQTT", "SIMULATOR"].includes(String(value.mode)) || typeof value.startImmediately !== "boolean") return null;
    for (const key of ["name", "type", "externalId", "profile", "preset"] as const) if (typeof value[key] !== "string" || String(value[key]).length > 255) return null;
    for (const key of ["deviceId", "sourceId", "locationId", "configurationId", "runId"] as const) if (value[key] !== undefined && (typeof value[key] !== "string" || !/^[a-zA-Z0-9_-]{1,128}$/.test(String(value[key])))) return null;
    if (value.pendingStage !== undefined && !["location", "device", "source", "credential", "configuration", "run"].includes(String(value.pendingStage))) return null;
    return Object.fromEntries(fields.filter((key) => key in value).map((key) => [key, value[key]])) as unknown as DeviceSetupCheckpoint;
  });
}
