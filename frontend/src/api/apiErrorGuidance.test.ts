import { afterEach, expect, it, vi } from "vitest";
import { ApiError, apiRequest, clearTokens } from "./client";
import { commandGuidance } from "../components/useEntityCommands";
afterEach(() => { clearTokens(); vi.restoreAllMocks(); });
it("keeps typed validation fields while removing credential and provider details", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ code: "VALIDATION_FAILED", message: "Provider https://api.telegram.org/botsecret token=private", fieldErrors: { name: "Required", password: "private", channel: "Webhook https://private.local/key" } }), { status: 422 }));
  const error = await apiRequest("/channels").catch((error) => error);
  if (!(error instanceof ApiError)) throw new Error("Expected typed API rejection");
  expect(error).toMatchObject({ status: 422, code: "VALIDATION_FAILED", fieldErrors: { name: "Required" } });
  expect(JSON.stringify({ message: error.message, fields: error.fieldErrors })).not.toMatch(/private|botsecret|https:/);
  expect(commandGuidance(error)).toContain("form fields");
});
it("reports authentication separately from service unavailability", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("{}", { status: 401 }));
  const error = await apiRequest("/devices").catch((error) => error);
  expect(commandGuidance(error)).toContain("session"); expect(commandGuidance(error)).not.toContain("service");
});
