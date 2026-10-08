import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ReadRefresh } from "./ReadRefresh";
import { EvidenceAge } from "./EvidenceAge";

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); localStorage.clear(); });
it("updates evidence age without a read request", () => {
  vi.useFakeTimers(); vi.setSystemTime(new Date("2026-10-08T00:00:00Z"));
  render(<EvidenceAge value="2026-10-08T00:00:00Z" />);
  expect(screen.getByText(/0s ago/)).toBeInTheDocument();
  act(() => vi.advanceTimersByTime(30_000));
  expect(screen.getByText(/30s ago/)).toBeInTheDocument();
});
it("polls reads with backoff, pauses hidden views, and checks authorization on resume", async () => {
  vi.useFakeTimers(); vi.spyOn(Math, "random").mockReturnValue(0);
  let hidden = false;
  vi.spyOn(document, "hidden", "get").mockImplementation(() => hidden);
  localStorage.setItem("voltcanary.accessToken", "fixture");
  const refresh = vi.fn().mockResolvedValueOnce(false).mockResolvedValue(true);
  render(<ReadRefresh scope="A" refresh={refresh} />);
  fireEvent.change(screen.getByLabelText("Read refresh"), { target: { value: "30000" } });
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(refresh).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(refresh).toHaveBeenCalledTimes(1);
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(refresh).toHaveBeenCalledTimes(2);
  hidden = true; fireEvent(document, new Event("visibilitychange"));
  await act(() => vi.advanceTimersByTimeAsync(300_000));
  expect(refresh).toHaveBeenCalledTimes(2);
  localStorage.clear(); hidden = false; fireEvent(document, new Event("visibilitychange"));
  await act(() => vi.advanceTimersByTimeAsync(30_000));
  expect(refresh).toHaveBeenCalledTimes(2);
});
