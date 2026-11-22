import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { getTelemetryFields, type TelemetryField } from "../../../telemetry/telemetryFieldsApi";
import { TelemetryFieldTable } from "./TelemetryFieldTable";

vi.mock("../../../telemetry/telemetryFieldsApi", () => ({ getTelemetryFields: vi.fn() }));

afterEach(() => { cleanup(); vi.useRealTimers(); vi.resetAllMocks(); });

it("waits for a slow read to finish before scheduling the next poll", async () => {
  vi.useFakeTimers();
  let finish!: (fields: TelemetryField[]) => void;
  vi.mocked(getTelemetryFields).mockImplementation(() => new Promise(resolve => { finish = resolve; }));
  render(<TelemetryFieldTable deviceId="device-a" />);
  await act(() => vi.advanceTimersByTimeAsync(20_000));
  expect(getTelemetryFields).toHaveBeenCalledTimes(1);
  await act(async () => { finish([]); });
  await act(() => vi.advanceTimersByTimeAsync(4999));
  expect(getTelemetryFields).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(1));
  expect(getTelemetryFields).toHaveBeenCalledTimes(2);
});

it("aborts a previous device read and ignores its late response", async () => {
  let finishOld!: (fields: TelemetryField[]) => void;
  vi.mocked(getTelemetryFields).mockImplementation(device => device === "device-a"
    ? new Promise(resolve => { finishOld = resolve; }) : Promise.resolve([]));
  const view = render(<TelemetryFieldTable deviceId="device-a" />);
  const oldSignal = vi.mocked(getTelemetryFields).mock.calls[0][2];
  view.rerender(<TelemetryFieldTable deviceId="device-b" />);
  expect(oldSignal?.aborted).toBe(true);
  await act(async () => { finishOld([{ key: "private-old", label: "Old device field", type: "number", unit: "", value: 1, display: true, sourceId: "old", recordedAt: new Date().toISOString() }]); });
  expect(screen.queryByText("Old device field")).not.toBeInTheDocument();
});
