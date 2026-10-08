import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PlanReview } from "./PlanReview";
import { AgentPlan } from "./aiAgentApi";
import { buildAssistantContext, inspectAssistantContext, redactAssistantDetails } from "./assistantContext";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const plan: AgentPlan = { planId: "plan-id", summary: "Inspect kitchen fridge", explanation: "Reviewed device scope", confirmationRequired: true, safetyNotes: [], actions: [{ id: "step", tool: "disable_device", title: "Disable kitchen fridge", description: "Change registration status", requiresConfirmation: true, parameters: { deviceId: "canonical-device", apiKey: "hidden-value" } }] };
describe("assistant review boundary", () => {
  it("projects selected ML and maintenance task names without credentials", () => {
    const context = buildAssistantContext("/settings/ml", "?tab=training&scope=device&deviceId=device-1&datasetVersionId=dataset-1&horizonDays=7&token=hidden", { names: { dataset: "July readings", device: "Kitchen fridge", password: "hidden" } as never });
    expect(context).toMatchObject({ page: "ML workspace", deviceId: "device-1", searchParams: { tab: "training", horizonDays: "7" }, names: { dataset: "July readings", device: "Kitchen fridge" } });
    expect(JSON.stringify(context)).not.toContain("hidden");
    expect(buildAssistantContext("/maintenance", "?operationId=operation-1&from=2026-10-01&to=2026-10-08", { names: { operation: "Inspect compressor" } })).toMatchObject({ page: "Maintenance", names: { operation: "Inspect compressor" }, searchParams: { operationId: "operation-1" } });
  });
  it("cancellation sends no execution request; confirmation executes only the reviewed plan once", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({ planId: plan.planId, status: "COMPLETED", actions: [{ actionId: "step", tool: "disable_device", status: "SUCCEEDED", result: { route: "/devices/canonical-device" }, error: null }] }), { status: 200 }));
    render(<MemoryRouter><PlanReview plan={plan} /></MemoryRouter>);
    expect(screen.queryByText(/hidden-value/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }));
    expect(fetch).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm and execute plan" }));
    expect(await screen.findByRole("link", { name: "Open result" })).toHaveAttribute("href", "/devices/canonical-device");
    expect(screen.getByRole("button", { name: "Execution recorded" })).toBeDisabled();
    expect(fetch).toHaveBeenCalledOnce();
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toEqual({ confirmed: true });
    expect(String(fetch.mock.calls[0][0])).toContain("/plans/plan-id/execute");
  });
  it("allows read-only execution without a mutation confirmation", async () => {
    const fetch = vi.spyOn(globalThis,"fetch").mockResolvedValue(new Response(JSON.stringify({ planId: "read", status: "COMPLETED", actions: [] }), { status: 200 }));
    render(<MemoryRouter><PlanReview plan={{ ...plan, planId: "read", confirmationRequired: false, actions: [{ ...plan.actions[0], tool: "get_device", requiresConfirmation: false }] }} /></MemoryRouter>);
    await userEvent.click(screen.getByRole("button", { name: "Run read-only" }));
    expect(await screen.findByRole("button", { name: "Execution recorded" })).toBeDisabled();
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toEqual({ confirmed: false });
  });
  it("keeps URL credentials out of context and rejects nested credentials in manual context", () => {
    expect(buildAssistantContext("/devices/canonical-device", "?metric=power&token=secret&password=secret")).toMatchObject({ deviceId: "canonical-device", searchParams: { metric: "power" } });
    expect(() => inspectAssistantContext('{"nested":{"apiKey":"secret"}}')).toThrow("Remove credentials");
    expect(() => inspectAssistantContext("[]")).toThrow("JSON object");
    expect(redactAssistantDetails({ token: "secret", steps: [{ deviceId: "device", password: "secret" }] })).toEqual({ token: "[redacted]", steps: [{ deviceId: "device", password: "[redacted]" }] });
  });
});
