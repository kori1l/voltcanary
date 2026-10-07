import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { SectionFeedback } from "./SectionFeedback";
import { SectionResource } from "./useSectionResource";

afterEach(cleanup);
const ready: SectionResource<unknown> = { data: [1], status: "ready", error: null, receivedAt: "2026-10-07T12:00:00Z", refreshing: false };
it("does not stack timestamps for independently loaded healthy sections", () => {
  const { container } = render(<>{["risk", "baseline", "prediction"].map(label => <SectionFeedback key={label} resource={ready} label={label} />)}</>);
  expect(container).toBeEmptyDOMElement();
});
it("keeps named stale evidence and a working retry", () => {
  const retry = vi.fn();
  render(<SectionFeedback resource={{ ...ready, status: "stale", error: "Gateway unavailable" }} label="risk" onRetry={retry} />);
  expect(screen.getByText(/risk last refreshed/)).toBeInTheDocument();
  expect(screen.getByRole("alert")).toHaveTextContent("risk: showing stale data");
  fireEvent.click(screen.getByRole("button", { name: "Retry risk" }));
  expect(retry).toHaveBeenCalledOnce();
});
it("identifies active refresh while retaining its retrieval time", () => {
  render(<SectionFeedback resource={{ ...ready, refreshing: true }} label="telemetry" />);
  expect(screen.getByText(/telemetry last refreshed/)).toHaveTextContent("Refreshing");
});
