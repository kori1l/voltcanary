import { act, cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it } from "vitest";
import { createMemoryRouter, Link, RouterProvider } from "react-router-dom";
import { clearTokens, setTokens } from "../api/client";
import { WorkflowDraftProvider } from "./WorkflowDraftGuard";
import { useConsoleQuery } from "../app/useConsoleQuery";
import { useScopedDraftValue } from "./useScopedDraftValue";
import { useWorkflowDraft } from "./workflowDraftContext";
import { draftScope, scopedDraftKey } from "./scopedDrafts";

afterEach(() => { cleanup(); clearTokens(); localStorage.clear(); });
function Editor() {
  const query = useConsoleQuery(); const [tab, setTab] = query.state<string>("tab", "settings");
  return <><form><label>Draft title<input defaultValue="Original" /></label></form><Link to="/devices">Leave route</Link><button onClick={() => setTab("training")}>Training tab</button><p>Tab {tab}</p></>;
}
function PersistedEditor() {
  const scope = draftScope("account-1", "training", "v1");
  const [draft, setDraft, clear, recovered] = useScopedDraftValue(scope, { title: "Original" }, (value) => typeof value.title === "string" ? { title: value.title } : null);
  useWorkflowDraft(recovered, clear);
  return <><form><label>Draft title<input value={draft.title} onChange={(event) => setDraft({ title: event.target.value })} /></label></form><Link to="/devices">Leave route</Link></>;
}
it("discards a newly persisted controlled draft as well as its visible form", async () => {
  setTokens(`${btoa("{}")}\.${btoa(JSON.stringify({ sub: "account-1" }))}.fixture`, "fixture");
  const router = createMemoryRouter([{ path: "/settings/ml", element: <WorkflowDraftProvider><PersistedEditor /></WorkflowDraftProvider> }, { path: "/devices", element: <p>Devices destination</p> }], { initialEntries: ["/settings/ml"] });
  render(<RouterProvider router={router} />);
  await userEvent.type(screen.getByLabelText("Draft title"), " edited");
  const key = scopedDraftKey(draftScope("account-1", "training", "v1")!);
  expect(localStorage.getItem(key)).toContain("edited");
  await userEvent.click(screen.getByText("Leave route"));
  await userEvent.click(await screen.findByRole("button", { name: "Discard changes" }));
  await screen.findByText("Devices destination");
  expect(localStorage.getItem(key)).toBeNull();
  await act(() => router.navigate(-1));
  expect(await screen.findByLabelText("Draft title")).toHaveValue("Original");
});
it("keeps dirty route/tab drafts and proceeds only after explicit discard", async () => {
  setTokens("fixture", "fixture");
  const router = createMemoryRouter([{ path: "/settings/ml", element: <WorkflowDraftProvider><Editor /></WorkflowDraftProvider> }, { path: "/devices", element: <p>Devices destination</p> }], { initialEntries: ["/settings/ml"] });
  render(<RouterProvider router={router} />);
  await userEvent.type(screen.getByLabelText("Draft title"), " edited");
  await userEvent.click(screen.getByText("Leave route"));
  await screen.findByRole("dialog", { name: "Unsaved changes" });
  await userEvent.click(screen.getByRole("button", { name: "Keep editing" }));
  expect(screen.getByLabelText("Draft title")).toHaveValue("Original edited");
  expect(router.state.location.pathname).toBe("/settings/ml");
  await userEvent.click(screen.getByRole("button", { name: "Training tab" }));
  await screen.findByRole("dialog", { name: "Unsaved changes" });
  await userEvent.click(screen.getByRole("button", { name: "Keep editing" }));
  expect(router.state.location.search).toBe("");
  await userEvent.click(screen.getByText("Leave route"));
  await userEvent.click(await screen.findByRole("button", { name: "Discard changes" }));
  await screen.findByText("Devices destination");
});
it("protects browser Back through the data router", async () => {
  setTokens("fixture", "fixture");
  const router = createMemoryRouter([{ path: "/settings/ml", element: <WorkflowDraftProvider><Editor /></WorkflowDraftProvider> }, { path: "/devices", element: <p>Devices destination</p> }], { initialEntries: ["/devices", "/settings/ml"], initialIndex: 1 });
  render(<RouterProvider router={router} />);
  await userEvent.type(screen.getByLabelText("Draft title"), " edited");
  await act(() => router.navigate(-1));
  await screen.findByRole("dialog", { name: "Unsaved changes" });
  await userEvent.click(screen.getByRole("button", { name: "Keep editing" }));
  await waitFor(() => expect(router.state.location.pathname).toBe("/settings/ml"));
});
