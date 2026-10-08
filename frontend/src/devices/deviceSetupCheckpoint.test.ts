import { afterEach, expect, it } from "vitest";
import { clearTokens, setTokens } from "../api/client";
import { readDeviceSetupCheckpoint, writeDeviceSetupCheckpoint, type DeviceSetupCheckpoint } from "./deviceSetupCheckpoint";
import { draftScope, scopedDraftKey } from "../components/scopedDrafts";
afterEach(() => { clearTokens(); localStorage.clear(); });
const login = (sub: string) => setTokens(`${btoa("{}")}\.${btoa(JSON.stringify({ sub }))}.fixture`, "fixture");
const account = { id: "account-A", userId: "user-A", name: "Private", status: "ACTIVE" as const };
const checkpoint: DeviceSetupCheckpoint = { deviceId: "device-canonical", sourceId: "source-canonical", name: "Test fridge", type: "FRIDGE", mode: "API", externalId: "plug-1", profile: "FRIDGE", preset: "NORMAL_OPERATION", startImmediately: false };
it("restores canonical references across reload without retaining plaintext credentials", () => {
  login("user-A");
  writeDeviceSetupCheckpoint(account, { ...checkpoint, sourceToken: "must-not-be-stored", metadata: { password: "private" } } as DeviceSetupCheckpoint);
  const stored = localStorage.getItem(scopedDraftKey(draftScope(account.id, "device-setup", "v1")!))!;
  expect(stored).not.toMatch(/must-not-be-stored|password|metadata/);
  login("user-A"); expect(readDeviceSetupCheckpoint(account)).toEqual(checkpoint);
  login("user-B"); expect(readDeviceSetupCheckpoint(account)).toBeNull();
});
it("rejects corrupt recovery data and account metadata belonging to another user", () => {
  login("user-A");
  const key = scopedDraftKey(draftScope(account.id, "device-setup", "v1")!);
  localStorage.setItem(key, "corrupt"); expect(readDeviceSetupCheckpoint(account)).toBeNull();
  expect(writeDeviceSetupCheckpoint({ ...account, userId: "another-user" }, checkpoint)).toBe(false);
});
