import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { AssistantPlanProgress } from "./AssistantPlanProgress";
import type { OrchestrationProgress } from "./conversationApi";

it("offers receipt reconciliation separately from preparing a failed step again", () => {
  const onControl = vi.fn();
  const plan: OrchestrationProgress = {
    version: "assistant-v2",
    planId: "plan",
    revision: 3,
    status: "ACTIVE",
    steps: [
      { stepId: "device", intentId: "CREATE_DEVICE", status: "SUCCEEDED", dependsOn: [] },
      {
        stepId: "source",
        intentId: "CREATE_DATA_SOURCE",
        status: "UNKNOWN",
        dependsOn: ["device"],
      },
      {
        stepId: "dashboard",
        intentId: "CREATE_DASHBOARD",
        status: "FAILED",
        dependsOn: ["source"],
      },
    ],
  };
  const { rerender } = render(
    <AssistantPlanProgress plan={plan} busy={false} onControl={onControl} />,
  );
  fireEvent.click(screen.getByRole("button", { name: "Проверить результат выполнения" }));
  expect(onControl).toHaveBeenLastCalledWith("RETRY", "source");
  fireEvent.click(screen.getByRole("button", { name: "Разрешить повторную подготовку" }));
  expect(onControl).toHaveBeenLastCalledWith("RETRY", "dashboard");
  rerender(
    <AssistantPlanProgress
      plan={{ ...plan, status: "CANCELLED" }}
      busy={false}
      onControl={onControl}
    />,
  );
  expect(screen.queryByRole("button", { name: "Разрешить повторную подготовку" })).toBeNull();
  expect(screen.getByRole("button", { name: "Проверить результат выполнения" })).toBeTruthy();
});
