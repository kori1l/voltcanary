import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, expect, it, vi } from "vitest";
import { CopyIdentifier } from "./CopyIdentifier";
afterEach(() => { cleanup(); vi.restoreAllMocks(); });
it("announces clipboard rejection and leaves the canonical ID selectable", async () => {
  const user = userEvent.setup(); vi.spyOn(navigator.clipboard, "writeText").mockRejectedValue(new Error("Denied"));
  render(<CopyIdentifier value="canonical-id" />);
  await user.click(screen.getByText("Copy ID"));
  expect(await screen.findByRole("status")).toHaveTextContent("Copy failed");
  expect(screen.getByText("canonical-id")).toHaveAttribute("tabindex", "0");
  vi.mocked(navigator.clipboard.writeText).mockResolvedValueOnce();
  await user.click(screen.getByText("Copy ID"));
  expect(screen.getByRole("status")).toHaveTextContent("ID copied.");
});
