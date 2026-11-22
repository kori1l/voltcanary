import { afterEach, expect, it, vi } from "vitest";
import {
  ApiError,
  apiRequest,
  apiResponseRequest,
  apiTextRequest,
  clearTokens,
  getAccessToken,
  setTokens,
} from "./client";

afterEach(() => {
  clearTokens();
  vi.restoreAllMocks();
});

it("preserves the Response and text contracts for 204 responses", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(async () => new Response(null, { status: 204 }));
  expect((await apiResponseRequest("/devices/id")).status).toBe(204);
  expect(await apiTextRequest("/devices/id")).toBe("");
  expect(await apiRequest("/devices/id")).toBeUndefined();
});

it("handles malformed error fields without throwing from the error handler", async () => {
  vi.spyOn(globalThis, "fetch").mockImplementation(
    async () => new Response(JSON.stringify({ message: { internal: "private" } }), { status: 503 }),
  );
  await expect(apiRequest("/devices")).rejects.toBeInstanceOf(ApiError);
  await expect(apiRequest("/devices")).rejects.toMatchObject({ status: 503 });
});

it("does not accept non-string credentials from a refresh response", async () => {
  setTokens("old", "refresh");
  vi.spyOn(globalThis, "fetch").mockImplementation(async (url) =>
    String(url).endsWith("/auth/refresh")
      ? new Response(JSON.stringify({ accessToken: 1, refreshToken: {} }))
      : new Response("{}", { status: 401 }),
  );
  await expect(apiRequest("/devices")).rejects.toMatchObject({ status: 503 });
  expect(getAccessToken()).toBe("old");
});

it("preserves lowercase identity error codes without changing their contract", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(
      JSON.stringify({
        code: "duplicate_email",
        message: "Email is already registered",
      }),
      { status: 409 },
    ),
  );
  await expect(apiRequest("/auth/register")).rejects.toMatchObject({
    status: 409,
    code: "duplicate_email",
  });
});
