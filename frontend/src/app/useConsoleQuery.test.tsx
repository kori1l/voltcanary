import { act, cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it } from "vitest";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { useConsoleQuery } from "./useConsoleQuery";
afterEach(cleanup);
function Context() {
  const query = useConsoleQuery(); const [tab, setTab] = query.state<string>("tab", "settings");
  return <><p>Tab {tab}</p><p>Dataset {query.params.get("datasetVersionId")}</p><button onClick={() => { setTab("training"); query.update({ datasetVersionId: "dataset-B", family: "FAILURE_PREDICTION", horizonDays: 7 }); }}>Choose task</button></>;
}
it("merges synchronous context updates and restores Back and reload", async () => {
  const routes = [{ path: "/settings/ml", element: <Context /> }];
  const router = createMemoryRouter(routes, { initialEntries: ["/settings/ml?tab=models&datasetVersionId=dataset-A"] });
  const rendered = render(<RouterProvider router={router} />);
  await userEvent.click(screen.getByText("Choose task"));
  expect(router.state.location.search).toContain("tab=training");
  expect(router.state.location.search).toContain("horizonDays=7");
  await act(() => router.navigate(-1));
  await screen.findByText("Tab models");
  const route = router.state.location.pathname + router.state.location.search;
  rendered.unmount();
  render(<RouterProvider router={createMemoryRouter(routes, { initialEntries: [route] })} />);
  expect(screen.getByText("Tab models")).toBeInTheDocument();
  expect(screen.getByText("Dataset dataset-A")).toBeInTheDocument();
});
