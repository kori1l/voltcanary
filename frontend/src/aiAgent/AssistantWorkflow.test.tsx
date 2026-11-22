import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { StrictMode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { PlanReview } from "./PlanReview";
import { AgentPlan } from "./aiAgentApi";
import {
  buildAssistantContext,
  inspectAssistantContext,
  redactAssistantDetails,
} from "./assistantContext";
import { clearTokens } from "../api/client";

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});
const plan: AgentPlan = {
  planId: "plan-id",
  summary: "Inspect kitchen fridge",
  explanation: "Reviewed device scope",
  confirmationRequired: true,
  safetyNotes: [],
  actions: [
    {
      id: "step",
      tool: "disable_device",
      title: "Disable kitchen fridge",
      description: "Change registration status",
      requiresConfirmation: true,
      parameters: { deviceId: "canonical-device", apiKey: "hidden-value" },
    },
  ],
};
describe("assistant review boundary", () => {
  it("disables execution and status checks while a replacement plan is being clarified", () => {
    const fetch = vi.spyOn(globalThis, "fetch");
    render(
      <MemoryRouter>
        <PlanReview plan={{ ...plan, version: 1 }} onClarify={vi.fn()} clarifying />
      </MemoryRouter>,
    );
    expect(screen.getByRole("button", { name: "Review and execute" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Check task status" })).toBeDisabled();
    expect(fetch).not.toHaveBeenCalled();
  });
  it("keeps the Vehicles page identity and safe return route", () => {
    expect(buildAssistantContext("/vehicles", "?token=hidden")).toMatchObject({
      page: "Vehicles",
      pageLabel: "Vehicles",
      route: "/vehicles",
      searchParams: {},
    });
  });
  it("projects selected ML and maintenance task names without credentials", () => {
    const context = buildAssistantContext(
      "/settings/ml",
      "?tab=training&scope=device&deviceId=device-1&datasetVersionId=dataset-1&horizonDays=7&token=hidden",
      {
        names: { dataset: "July readings", device: "Kitchen fridge", password: "hidden" } as never,
      },
    );
    expect(context).toMatchObject({
      page: "ML workspace",
      deviceId: "device-1",
      searchParams: { tab: "training", horizonDays: "7" },
      names: { dataset: "July readings", device: "Kitchen fridge" },
    });
    expect(JSON.stringify(context)).not.toContain("hidden");
    expect(
      buildAssistantContext(
        "/maintenance",
        "?operationId=operation-1&from=2026-10-01&to=2026-10-08",
        { names: { operation: "Inspect compressor" } },
      ),
    ).toMatchObject({
      page: "Maintenance",
      names: { operation: "Inspect compressor" },
      searchParams: { operationId: "operation-1" },
    });
  });
  it("cancellation sends no execution request; confirmation executes only the reviewed plan once", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          planId: plan.planId,
          status: "COMPLETED",
          actions: [
            {
              actionId: "step",
              tool: "disable_device",
              status: "SUCCEEDED",
              result: { route: "/devices/canonical-device" },
              error: null,
            },
          ],
        }),
        { status: 200 },
      ),
    );
    render(
      <MemoryRouter>
        <PlanReview plan={plan} />
      </MemoryRouter>,
    );
    expect(screen.queryByText(/hidden-value/)).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
    await userEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "Cancel" }),
    );
    expect(fetch).not.toHaveBeenCalled();
    await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
    await userEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm and execute plan" }),
    );
    expect(await screen.findByRole("link", { name: "Open result" })).toHaveAttribute(
      "href",
      "/devices/canonical-device",
    );
    expect(screen.getByRole("button", { name: "Execution recorded" })).toBeDisabled();
    expect(fetch).toHaveBeenCalledOnce();
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toEqual({ confirmed: true });
    expect(String(fetch.mock.calls[0][0])).toContain("/plans/plan-id/execute");
  });
  it("allows read-only execution without a mutation confirmation", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ planId: "read", status: "COMPLETED", actions: [] }), {
        status: 200,
      }),
    );
    render(
      <MemoryRouter>
        <PlanReview
          plan={{
            ...plan,
            planId: "read",
            confirmationRequired: false,
            actions: [{ ...plan.actions[0], tool: "get_device", requiresConfirmation: false }],
          }}
        />
      </MemoryRouter>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Run read-only" }));
    expect(await screen.findByRole("button", { name: "Execution recorded" })).toBeDisabled();
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toEqual({ confirmed: false });
  });
  it("keeps URL credentials out of context and rejects nested credentials in manual context", () => {
    expect(
      buildAssistantContext(
        "/devices/canonical-device",
        "?metric=power&token=secret&password=secret",
      ),
    ).toMatchObject({ deviceId: "canonical-device", searchParams: { metric: "power" } });
    expect(() => inspectAssistantContext('{"nested":{"apiKey":"secret"}}')).toThrow(
      "Remove credentials",
    );
    expect(() => inspectAssistantContext("[]")).toThrow("JSON object");
    expect(
      redactAssistantDetails({
        token: "secret",
        steps: [{ deviceId: "device", password: "secret" }],
      }),
    ).toEqual({ token: "[redacted]", steps: [{ deviceId: "device", password: "[redacted]" }] });
    expect(
      buildAssistantContext("/ai-agent", "?deviceId=device-1&workflow=dashboard&token=secret"),
    ).toMatchObject({
      deviceId: "device-1",
      searchParams: { deviceId: "device-1", workflow: "dashboard" },
    });
    const binding = "00000000-0000-4000-8000-000000000001";
    expect(
      buildAssistantContext(
        "/ai-agent",
        `?workflow=source-mapping&sourceBinding=${binding}%3A2&token=secret`,
      ).searchParams,
    ).toEqual({ workflow: "source-mapping", sourceBinding: `${binding}:2` });
    expect(
      buildAssistantContext(
        "/ai-agent",
        `?workflow=source-mapping&sourceBinding=${binding}:9007199254740992`,
      ).searchParams,
    ).toEqual({ workflow: "source-mapping" });
  });
  it("invalidates an open confirmation when the reviewed version changes", async () => {
    const fetch = vi.spyOn(globalThis, "fetch");
    const view = render(
      <MemoryRouter>
        <PlanReview plan={{ ...plan, version: 1, payloadDigest: "first" }} />
      </MemoryRouter>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
    view.rerender(
      <MemoryRouter>
        <PlanReview plan={{ ...plan, version: 2, payloadDigest: "second" }} />
      </MemoryRouter>,
    );
    await userEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm and execute plan" }),
    );
    expect(fetch).not.toHaveBeenCalled();
  });
  it("keeps clarification and expired plans non-executable", () => {
    const view = render(
      <MemoryRouter>
        <PlanReview
          plan={{
            ...plan,
            actions: [],
            planningStatus: "NEEDS_INPUT",
            questions: ["Как назвать устройство?"],
          }}
        />
      </MemoryRouter>,
    );
    expect(screen.getByText("Как назвать устройство?")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Clarification required" })).toBeDisabled();
    view.rerender(
      <MemoryRouter>
        <PlanReview plan={{ ...plan, expiresAt: "2020-01-01T00:00:00Z" }} />
      </MemoryRouter>,
    );
    expect(screen.getByRole("button", { name: "Review and execute" })).toBeDisabled();
  });
  it("sends the immutable reviewed version and digest", async () => {
    const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ planId: plan.planId, status: "SUCCESS", actions: [] }), {
        status: 200,
      }),
    );
    render(
      <StrictMode>
        <MemoryRouter>
          <PlanReview plan={{ ...plan, version: 3, payloadDigest: "reviewed-digest" }} />
        </MemoryRouter>
      </StrictMode>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
    await userEvent.click(
      within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm and execute plan" }),
    );
    expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toEqual({
      confirmed: true,
      expectedVersion: 3,
      payloadDigest: "reviewed-digest",
    });
  });
  it("removes a reviewed task and prevents execution when the session changes", async () => {
    const fetch = vi.spyOn(globalThis, "fetch");
    render(
      <MemoryRouter>
        <PlanReview plan={plan} />
      </MemoryRouter>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
    act(() => clearTokens());
    expect(screen.getByRole("alert")).toHaveTextContent("Session changed");
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByText(plan.summary)).not.toBeInTheDocument();
    expect(fetch).not.toHaveBeenCalled();
  });
  it("checking a ready task does not record execution and server expiry disables execution", async () => {
    const fetch = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...plan, version: 1, executionStatus: "READY" }), {
          status: 200,
        }),
      )
      .mockResolvedValueOnce(
        new Response(JSON.stringify({ ...plan, version: 1, executionStatus: "EXPIRED" }), {
          status: 200,
        }),
      );
    render(
      <StrictMode>
        <MemoryRouter>
          <PlanReview plan={{ ...plan, version: 1, executionStatus: "READY" }} />
        </MemoryRouter>
      </StrictMode>,
    );
    await userEvent.click(screen.getByRole("button", { name: "Check task status" }));
    expect(screen.getByRole("button", { name: "Review and execute" })).toBeEnabled();
    await userEvent.click(screen.getByRole("button", { name: "Check task status" }));
    expect(
      await screen.findByText("Plan expired. Prepare and review a new task."),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Review and execute" })).toBeDisabled();
    expect(fetch).toHaveBeenCalledTimes(2);
  });
});
