import { afterEach, expect, it, vi } from "vitest";
import {
  apiRequest,
  clearTokens,
  getAccessToken,
  prepareAiSession,
  SESSION_EXPIRED,
  setTokens,
} from "./client";

afterEach(() => {
  clearTokens();
  vi.restoreAllMocks();
});
const json = (value: unknown, status = 200) => new Response(JSON.stringify(value), { status });
const jwt = (seconds: number) =>
  `header.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + seconds }))}.signature`;
it("renews near-expiry credentials once before concurrent AI preparations", async () => {
  setTokens(jwt(100), "refresh");
  const renewed = jwt(900);
  const fetch = vi
    .spyOn(globalThis, "fetch")
    .mockResolvedValue(json({ accessToken: renewed, refreshToken: "rotated" }));
  await Promise.all([prepareAiSession(), prepareAiSession()]);
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(String(fetch.mock.calls[0][0])).toMatch(/\/auth\/refresh$/);
  expect(getAccessToken()).toBe(renewed);
});
it("does not replay a model request when proactive renewal is unavailable", async () => {
  setTokens(jwt(100), "refresh");
  const fetch = vi.spyOn(globalThis, "fetch").mockRejectedValue(new Error("offline"));
  await expect(prepareAiSession()).rejects.toMatchObject({ status: 503 });
  expect(fetch).toHaveBeenCalledTimes(1);
});
it("rejects a successful old-account read after switching login", async () => {
  setTokens("account-A", "refresh-A");
  let release!: (response: Response) => void;
  vi.spyOn(globalThis, "fetch").mockImplementation(
    () =>
      new Promise((resolve) => {
        release = resolve;
      }),
  );
  const request = apiRequest("/devices");
  setTokens("account-B", "refresh-B");
  release(json([{ id: "private-A" }]));
  await expect(request).rejects.toMatchObject({ status: 401 });
});
it("shares one refresh across simultaneous expired reads", async () => {
  setTokens("old", "refresh");
  let refreshCount = 0;
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    if (String(input).endsWith("/auth/refresh")) {
      refreshCount++;
      return json({ accessToken: "new", refreshToken: "next" });
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
    setTokens("old", "refresh");
    vi.spyOn(globalThis, "fetch").mockResolvedValue(json({ message: "Rejected" }, 401));
    await Promise.allSettled([apiRequest("/devices"), apiRequest("/alerts")]);
    expect(expired).toHaveBeenCalledTimes(1);
    expect(getAccessToken()).toBeNull();
    setTokens("old", "refresh");
    vi.mocked(fetch).mockImplementation(async (input) =>
      String(input).endsWith("/auth/refresh") ? json({}, 503) : json({}, 401),
    );
    await expect(apiRequest("/devices")).rejects.toMatchObject({ status: 503 });
    expect(getAccessToken()).toBe("old");
    expect(expired).toHaveBeenCalledTimes(1);
  } finally {
    window.removeEventListener(SESSION_EXPIRED, expired);
  }
});
it("does not publish an obsolete refresh or retry a mutation in a different login session", async () => {
  setTokens("account-A", "refresh-A");
  let release!: (response: Response) => void;
  const pending = new Promise<Response>((resolve) => {
    release = resolve;
  });
  const requests: string[] = [];
  vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
    requests.push(String(input));
    return String(input).endsWith("/auth/refresh") ? pending : json({}, 401);
  });
  const read = apiRequest("/analytics/ml-settings", { method: "PUT", body: "{}" });
  await vi.waitFor(() => expect(requests).toHaveLength(2));
  setTokens("account-B", "refresh-B");
  release(json({ accessToken: "obsolete-A", refreshToken: "obsolete-refresh-A" }));
  await expect(read).rejects.toMatchObject({ status: 401 });
  expect(getAccessToken()).toBe("account-B");
  expect(requests).toHaveLength(2);
});
