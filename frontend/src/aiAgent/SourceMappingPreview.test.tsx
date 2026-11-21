import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { PlanReview } from "./PlanReview";
import type { AgentPlan } from "./aiAgentApi";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });

function mappingPlan(value: boolean): AgentPlan {
  return {
    planId: "mapping-plan", version: 2, payloadDigest: "reviewed-mapping",
    summary: "Маппинг источника", explanation: "Проверьте значения", safetyNotes: [],
    confirmationRequired: true, planningStatus: "READY", executionStatus: "READY",
    actions: [{
      id: "mapping-action", tool: "APPLY_SOURCE_MAPPING", title: "Применить маппинг",
      description: "Преобразование в логическое значение", requiresConfirmation: true,
      parameters: {
        bindingId: "source-1", fieldId: "field-1", schemaRevision: 3,
        conversion: { method: "parse_boolean", trueTokens: ["on", "ON"], falseTokens: ["off"] },
        mappingExample: { sample: "off", literal: { type: "BOOLEAN", value } },
      },
    }],
  };
}

it("shows the exact mapping and sends only the confirmed review identity", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify({
    planId: "mapping-plan", status: "SUCCESS", actions: [],
  })));
  render(<MemoryRouter><PlanReview plan={mappingPlan(false)} /></MemoryRouter>);
  const preview = within(screen.getByRole("region", { name: "Предпросмотр маппинга" }));
  expect(preview.getByText("field-1")).toBeInTheDocument();
  expect(preview.getByText('"on", "ON"')).toBeInTheDocument();
  expect(preview.getByText("false", { selector: "code" })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
  expect(fetch).not.toHaveBeenCalled();
  await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm and execute plan" }));
  expect(await screen.findByText("Result: SUCCESS")).toBeInTheDocument();
  expect(fetch).toHaveBeenCalledOnce();
  expect(JSON.parse(String(fetch.mock.calls[0][1]?.body))).toEqual({
    confirmed: true, expectedVersion: 2, payloadDigest: "reviewed-mapping",
  });
});

it("blocks confirmation when the example contradicts the reviewed tokens", () => {
  const fetch = vi.spyOn(globalThis, "fetch");
  render(<MemoryRouter><PlanReview plan={mappingPlan(true)} /></MemoryRouter>);
  expect(screen.getByRole("alert")).toHaveTextContent("Пример преобразования недоступен.");
  expect(screen.getByRole("button", { name: "Review and execute" })).toBeDisabled();
  expect(fetch).not.toHaveBeenCalled();
});
