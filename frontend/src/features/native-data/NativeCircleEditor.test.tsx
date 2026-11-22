import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { NativeCircleEditor } from "./NativeCircleEditor";
import { listCircles, readCircleDependencies, saveCircle } from "./nativeCircleApi";

vi.mock("./nativeCircleApi", () => ({
  listCircles: vi.fn(),
  readCircle: vi.fn(),
  readCircleDependencies: vi.fn(),
  saveCircle: vi.fn(),
}));

it("reviews the pinned rule dependencies before saving revised geometry", async () => {
  const id = "3803f3f1-dee5-4787-851b-37d599617adf";
  const geometry = { longitude: 24, latitude: 60, radiusMetres: "100" };
  vi.mocked(listCircles).mockResolvedValue({
    items: [{ id, revision: 1, geometry, digest: "one" }],
    nextAfter: null,
  });
  vi.mocked(readCircleDependencies).mockResolvedValue({
    circleId: id,
    revision: 1,
    complete: true,
    dependencies: [
      { id: "deployment-id", kind: "RULE_DEPLOYMENT", ruleId: "rule-id", state: "ACTIVE" },
    ],
  });
  vi.mocked(saveCircle).mockResolvedValue({
    id,
    revision: 2,
    geometry: { ...geometry, radiusMetres: "200" },
    digest: "two",
  });
  render(<NativeCircleEditor />);
  fireEvent.change(await screen.findByLabelText("Saved zone"), { target: { value: id } });
  fireEvent.change(screen.getByLabelText("Radius (metres)"), { target: { value: "200" } });
  fireEvent.click(screen.getByRole("button", { name: "Review zone" }));
  expect(
    await screen.findByRole("list", { name: "Rules using the current zone revision" }),
  ).toHaveTextContent("rule-id");
  expect(readCircleDependencies).toHaveBeenCalledWith(id, 1, expect.any(AbortSignal));
  expect(saveCircle).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Confirm and save zone" }));
  expect(await screen.findByText(/revision 2\./)).toBeVisible();
  expect(saveCircle).toHaveBeenCalledWith(
    id,
    1,
    { ...geometry, radiusMetres: "200" },
    expect.any(AbortSignal),
  );
});
