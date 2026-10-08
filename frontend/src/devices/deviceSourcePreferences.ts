import { draftScope, readScopedDraft, saveScopedDraft, sessionUserId } from "../components/scopedDrafts";
const scope = (deviceId: string) => draftScope(sessionUserId(), `device-source.${deviceId}`, "source-id-v1");
export function readDeviceSourcePreference(deviceId: string | undefined) {
  return deviceId ? readScopedDraft(scope(deviceId), (value) => typeof value.externalId === "string" ? value.externalId : null) : null;
}
export function writeDeviceSourcePreference(deviceId: string, externalId: string) { saveScopedDraft(scope(deviceId), { externalId }, ["externalId"]); }
