import { cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import nativeAuthoring from "../../../backend/common/common-vcl/src/test/resources/native-authoring/native-table-authoring.json";
import { PlanReview } from "./PlanReview";
import type { AgentPlan } from "./aiAgentApi";

afterEach(() => { cleanup(); vi.restoreAllMocks(); });
const plan: AgentPlan = {
  planId: "native-plan", version: 1, payloadDigest: "reviewed-native", summary: "Create service visits", explanation: "Review the fields",
  confirmationRequired: true, safetyNotes: [], planningStatus: "READY", executionStatus: "READY",
  actions: [{ id: "native-action", tool: "CREATE_NATIVE_TABLE", title: "Create service visits", description: "Eleven typed fields",
    requiresConfirmation: true, parameters: { protocol: "native-table-review-1", document: nativeAuthoring.document } }],
};
const success = { planId: plan.planId, status: "SUCCESS", actions: [{ actionId: "native-action", tool: "CREATE_NATIVE_TABLE", status: "SUCCESS",
  result: { datasetId: nativeAuthoring.reservation.resourceId, route: `/data?datasetId=${nativeAuthoring.reservation.resourceId}` }, error: null }] };

it("renders the same concrete schema preview and executes only the explicitly confirmed immutable review", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(success), { status: 200 }));
  render(<MemoryRouter><PlanReview plan={plan}/></MemoryRouter>);
  const schema = screen.getByRole("region", { name: "Table schema preview" });
  expect(within(schema).getByRole("heading", { name: "Service visits" })).toBeInTheDocument();
  expect(within(schema).getByText(/Voltage: Decimal \(V\).*Precision 18, scale 3/)).toBeInTheDocument();
  expect(within(schema).getByText(/Status: Choice.*Queued, In progress/)).toBeInTheDocument();
  expect(within(schema).getByText(/Previous visit: Reference/)).toBeInTheDocument();
  expect(within(schema).getByText("Unique key: Visit code")).toBeInTheDocument();
  expect(within(schema).getByText("Indexed columns: Count, Voltage")).toBeInTheDocument();
  expect(within(schema).getByText("Keep row revisions: Yes")).toBeInTheDocument();
  expect(fetch).not.toHaveBeenCalled();
  await userEvent.click(screen.getByRole("button", { name: "Review and execute" }));
  expect(fetch).not.toHaveBeenCalled();
  await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Confirm and execute plan" }));
  expect(await screen.findByRole("link", { name: "Open result" })).toHaveAttribute("href", success.actions[0].result.route);
  expect(fetch).toHaveBeenCalledOnce();
  expect(JSON.parse(fetch.mock.calls[0][1]!.body as string)).toEqual({ confirmed: true, expectedVersion: 1, payloadDigest: "reviewed-native" });
});

it("cannot confirm a native action whose actual schema cannot be displayed", () => {
  const fetch = vi.spyOn(globalThis, "fetch");
  render(<MemoryRouter><PlanReview plan={{ ...plan, actions: [{ ...plan.actions[0], parameters: {} }] }}/></MemoryRouter>);
  expect(screen.getByRole("alert")).toHaveTextContent("Table preview unavailable");
  expect(screen.getByRole("button", { name: "Review and execute" })).toBeDisabled();
  expect(fetch).not.toHaveBeenCalled();
});

it("allows only saved receipt inspection for an expired UNKNOWN native review", async () => {
  const fetch = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(JSON.stringify(success), { status: 200 }));
  render(<MemoryRouter><PlanReview plan={{ ...plan, expiresAt: "2020-01-01T00:00:00Z", executionStatus: "UNKNOWN",
    execution: { planId: plan.planId, status: "UNKNOWN", actions: [] } }}/></MemoryRouter>);
  expect(screen.getByRole("button", { name: "Execution recorded" })).toBeDisabled();
  await userEvent.click(screen.getByRole("button", { name: "Inspect saved table receipt" }));
  expect(await screen.findByRole("link", { name: "Open result" })).toHaveAttribute("href", success.actions[0].result.route);
  expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  expect(fetch).toHaveBeenCalledOnce();
});

it("escapes field labels in the shared preview", () => {
  const changed = structuredClone(nativeAuthoring.document);
  changed.spec.fields[0].label = "<img src=x onerror=alert(1)>";
  render(<MemoryRouter><PlanReview plan={{ ...plan, actions: [{ ...plan.actions[0], parameters: { protocol: "native-table-review-1", document: changed } }] }}/></MemoryRouter>);
  expect(screen.getByText(/<img src=x onerror=alert\(1\)>: Text/)).toBeInTheDocument();
  expect(window.document.querySelector(".native-schema-preview img")).toBeNull();
});
