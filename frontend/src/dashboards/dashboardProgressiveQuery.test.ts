import { afterEach, expect, it, vi } from "vitest";
import { clearTokens } from "../api/client";
import {
  queryDashboard,
  type DashboardQueryBatch,
  type WidgetQueryPanel,
} from "./dashboardQueriesApi";
afterEach(() => {
  vi.restoreAllMocks();
  clearTokens();
});
const panel = (id: string): WidgetQueryPanel => ({
  widgetId: id,
  status: "ready",
  data: { scopeRef: "scope", series: [{ value: 7 }] },
  contextHash: "hash",
  time: null,
  requestedCount: 1,
  evaluatedCount: 1,
  receivedAt: "2026-10-11T00:00:00Z",
  measuredAt: null,
  provenance: {},
  errorCode: null,
  explanation: null,
});
const group = (id: string): DashboardQueryBatch => ({
  dashboardId: "dashboard",
  revision: 1,
  draftEtag: null,
  contextHash: "hash",
  asOf: "2026-10-11T00:00:00Z",
  returnedAt: "2026-10-11T00:00:00Z",
  cached: false,
  panels: [panel(id)],
});
it("renders the first split NDJSON panel before completion and shares scope references", async () => {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const stream = new ReadableStream<Uint8Array>({
    start(value) {
      controller = value;
    },
  });
  vi.spyOn(globalThis, "fetch").mockResolvedValue(
    new Response(stream, { headers: { "Content-Type": "application/x-ndjson" } }),
  );
  const progress = vi.fn(),
    encoder = new TextEncoder();
  const result = queryDashboard("dashboard", {}, new AbortController().signal, progress);
  const first =
    JSON.stringify({
      ...group("fast"),
      scopes: { scope: { devices: [{ id: "device", name: "Equipment" }], locations: [] } },
    }) + "\n";
  controller.enqueue(encoder.encode(first.slice(0, 60)));
  controller.enqueue(encoder.encode(first.slice(60)));
  await vi.waitFor(() => expect(progress).toHaveBeenCalledTimes(1));
  expect(progress.mock.calls[0][0].panels).toHaveLength(1);
  controller.enqueue(
    encoder.encode(
      JSON.stringify(group("slow")) +
        "\n" +
        JSON.stringify({ complete: true, contextHash: "hash" }) +
        "\n",
    ),
  );
  controller.close();
  const batch = await result;
  expect(batch.panels).toHaveLength(2);
  expect(batch.panels[0].data?.devices).toBe(batch.panels[1].data?.devices);
  expect(batch.panels[0].data?.series).toEqual([{ value: 7 }]);
});
it("rejects truncated streams and purges progress after an authentication switch", async () => {
  vi.spyOn(globalThis, "fetch").mockResolvedValueOnce(
    new Response(JSON.stringify(group("fast")) + "\n"),
  );
  await expect(queryDashboard("dashboard", {}, undefined, vi.fn())).rejects.toThrow(
    "before completion",
  );
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  vi.mocked(fetch).mockResolvedValueOnce(
    new Response(
      new ReadableStream<Uint8Array>({
        start(value) {
          controller = value;
        },
      }),
    ),
  );
  const progress = vi.fn();
  const result = queryDashboard("dashboard", {}, undefined, progress);
  await Promise.resolve();
  clearTokens();
  controller.enqueue(new TextEncoder().encode(JSON.stringify(group("obsolete")) + "\n"));
  await expect(result).rejects.toThrow("Authentication scope changed");
  expect(progress).not.toHaveBeenCalled();
});
