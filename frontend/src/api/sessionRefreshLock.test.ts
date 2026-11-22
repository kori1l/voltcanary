import { afterEach, expect, it, vi } from "vitest";
import { withSessionRefreshLock } from "./sessionRefreshLock";

afterEach(() => {
  localStorage.clear();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

it("serializes independent contenders when Web Locks are unavailable", async () => {
  vi.stubGlobal("navigator", { locks: undefined });
  let release!: () => void;
  const pending = new Promise<void>((resolve) => {
    release = resolve;
  });
  const order: string[] = [];
  const first = withSessionRefreshLock(async () => {
    order.push("first");
    await pending;
  });
  const second = withSessionRefreshLock(async () => {
    order.push("second");
  });
  await new Promise((resolve) => setTimeout(resolve, 40));
  expect(order).toEqual(["first"]);
  release();
  await Promise.all([first, second]);
  expect(order).toEqual(["first", "second"]);
  expect(localStorage.length).toBe(0);
});

it("releases a failed operation and ignores an expired crashed contender", async () => {
  vi.stubGlobal("navigator", { locks: undefined });
  localStorage.setItem(
    "voltcanary.refresh-lock.crashed",
    JSON.stringify({ choosing: true, ticket: 0, expires: Date.now() - 1 }),
  );
  await expect(
    withSessionRefreshLock(async () => {
      throw new Error("offline");
    }),
  ).rejects.toThrow("offline");
  await expect(withSessionRefreshLock(async () => "recovered")).resolves.toBe("recovered");
});

it("uses the browser's exclusive Web Lock when supported", async () => {
  const request = vi.fn(async (_name: string, operation: () => Promise<string>) => operation());
  vi.stubGlobal("navigator", { locks: { request } });
  await expect(withSessionRefreshLock(async () => "renewed")).resolves.toBe("renewed");
  expect(request).toHaveBeenCalledWith("voltcanary.session.refresh", expect.any(Function));
});

it("shares rotated credentials between independent client instances", async () => {
  vi.stubGlobal("navigator", { locks: undefined });
  vi.resetModules();
  const first = await import("./client");
  const jwt = (seconds: number) =>
    `header.${btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + seconds }))}.signature`;
  first.setTokens(jwt(100), "original-refresh");
  vi.resetModules();
  const second = await import("./client");
  const renewed = jwt(900);
  let finish!: (response: Response) => void;
  const fetch = vi.spyOn(globalThis, "fetch").mockImplementation(
    () =>
      new Promise((resolve) => {
        finish = resolve;
      }),
  );
  const a = first.prepareAiSession();
  const b = second.prepareAiSession();
  await vi.waitFor(() => expect(fetch).toHaveBeenCalledTimes(1));
  finish(
    new Response(JSON.stringify({ accessToken: renewed, refreshToken: "rotated-refresh" }), {
      status: 200,
    }),
  );
  await Promise.all([a, b]);
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(first.getAccessToken()).toBe(renewed);
  expect(second.getAccessToken()).toBe(renewed);
});
