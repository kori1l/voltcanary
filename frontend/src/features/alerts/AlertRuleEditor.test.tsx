import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { AlertRuleEditor } from "./AlertRuleEditor";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
describe("condition-aware rule editing", () => {
  it("tests a zero threshold in an explicit interval without creating a rule", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ previewTitle: "Threshold check", previewDescription: "Recorded readings only", evaluationStatus: "MATCHED", matchCount: 2 }), { status: 200 }));
    const saved = vi.fn();
    render(<AlertRuleEditor devices={[]} locations={[]} channels={[]} deviceId="canonical-device" from="2026-10-01T10:00:00Z" to="2026-10-01T11:00:00Z" onSaved={saved} />);
    await userEvent.selectOptions(screen.getByLabelText("Condition"), "ABOVE_THRESHOLD");
    await userEvent.type(screen.getByLabelText(/Threshold \(W\)/), "0");
    await userEvent.click(screen.getByRole("button", { name: "Test rule" }));
    expect(await screen.findByText(/Test only: Threshold check/)).toBeInTheDocument();
    expect(saved).not.toHaveBeenCalled();
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toMatchObject({ deviceId: "canonical-device", rule: { scopeId: "canonical-device", threshold: 0, thresholdUnit: "W", baselineMultiplier: null, minPoints: null }, from: "2026-10-01T10:00:00.000Z", to: "2026-10-01T11:00:00.000Z" });
  });
  it("does not require or send a stale multiplier for missing telemetry", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ id: "created" }), { status: 200 }));
    const saved = vi.fn();
    render(<AlertRuleEditor devices={[]} locations={[]} channels={[]} onSaved={saved} />);
    await userEvent.selectOptions(screen.getByLabelText("Condition"), "MISSING_TELEMETRY");
    expect(screen.queryByLabelText("Baseline multiplier")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Create rule" }));
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toMatchObject({ conditionType: "MISSING_TELEMETRY", baselineMultiplier: null, threshold: null });
    expect(saved).toHaveBeenCalledWith({ id: "created" });
  });
});
