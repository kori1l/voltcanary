import { afterEach, expect, it, vi } from "vitest";
import {
  apiRequest,
  clearTokens,
  getAccessToken,
  prepareAiSession,
  SESSION_EXPIRED,
  setSession,
} from "./client";

afterEach(() => {
  clearTokens();
  vi.restoreAllMocks();
});
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const jwt = (seconds: number) =>
  `header.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + seconds }))}.signature`;
it("renews near-expiry credentials once before concurrent AI preparations", async () => {
  setSession(jwt(100), "refresh");
  const renewed = jwt(900);
  const fetch = vi
    .spyOn(globalThis, "fetch")
    .mockResolvedValue(json({ accessToken: renewed, sessionId: "refresh" }));
  await Promise.all([prepareAiSession(), prepareAiSession()]);
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(String(fetch.mock.calls[0][0])).toMatch(/\/auth\/browser\/refresh\/refresh$/);
  expect(getAccessToken()).toBe(renewed);
  expect(fetch.mock.calls[0][1]).toMatchObject({ credentials: "include", body: "{}" });
  expect(localStorage.getItem("voltcanary.refreshToken")).toBeNull();
});
it("does not replay a model request when proactive renewal is unavailable", async () => {
  setSession(jwt(100), "refresh");
  const fetch = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("offline"));
  await expect(prepareAiSession()).rejects.toMatchObject({ status: 503 });
  expect(fetch).toHaveBeenCalledTimes(1);
});
it("rejects a successful old-account read after switching login", async () => {
  setSession("account-A", "refresh-A");
  let release!: (response: Response) => void;
  vi.spyOn(globalThis, "fetch").mockImplementation(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const request = apiRequest("/devices");
  setSession("account-B", "refresh-B");
  release(json([{ id: "private-A" }]));
  await expect(request).rejects.toMatchObject({ status: 401 });
});
it("shares one refresh across simultaneous expired reads", async () => {
  setSession("old", "refresh");
  let refreshCount = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (String(input).endsWith("/refresh")) {
      refreshCount++;
      return json({ accessToken: "new", sessionId: "refresh" });
    }
    return new Headers(init?.headers).get("Authorization") === "Bearer old"
      ? json({ message: "Expired" }, 401)
      : json({ ready: true });
  });
  expect(
    await Promise.all([
      apiRequest("/devices"),
      apiRequest("/alerts"),
      apiRequest("/analytics/ml/datasets"),
    ]),
  ).toEqual([{ ready: true }, { ready: true }, { ready: true }]);
  expect(refreshCount).toBe(1);
});
it("expires the session once on definitive rejection and preserves it on temporary failure", async () => {
  const expired = vi.fn();
  window.addEventListener(SESSION_EXPIRED, expired);
  try {
    setSession("old", "refresh");
    vi.spyOn(globalThis, "fetch").mockResolvedValue(json({ message: "Rejected" }, 401));
    await Promise.allSettled([apiRequest("/devices"), apiRequest("/alerts")]);
    expect(expired).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBeNull();
    setSession("old", "refresh");
    vi.mocked(fetch).mockImplementation(async (input) =>
      String(input).endsWith("/refresh") ? json({}, 503) : json({}, 401),
    );
    await expect(apiRequest("/devices")).rejects.toMatchObject({ status: 503 });
    expect(getAccessToken()).toBe("old");
    expect(expired).toHaveBeenCalledTimes(1);
  } finally {
    window.removeEventListener(SESSION_EXPIRED, expired);
  }
});
it("does not publish an obsolete refresh or retry a mutation in a different login session", async () => {
  setSession("account-A", "refresh-A");
  let release!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => {
    release = resolve;
  });
  const requests: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    requests.push(String(input));
    return String(input).endsWith("/refresh") ? pending : json({}, 401);
  });
  const read = apiRequest("/analytics/ml-settings", { method: "PUT", body: "{}" });
  await vi.waitFor(() => expect(requests).toHaveLength(2));
  setSession("account-B", "refresh-B");
  release(json({ accessToken: "obsolete-A", sessionId: "refresh-A" }));
  await expect(read).rejects.toMatchObject({ status: 401 });
  expect(getAccessToken()).toBe("account-B");
  expect(requests).toHaveLength(2);
});
