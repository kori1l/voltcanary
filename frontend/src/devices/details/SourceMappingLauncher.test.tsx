import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes, useLocation } from "react-router-dom";
import { afterEach, expect, it, vi } from "vitest";
import { SourceMappingLauncher } from "./SourceMappingLauncher";

const sourceId = "00000000-0000-4000-8000-000000000001";

function Destination() {
  const { search } = useLocation();
  return <output>{new URLSearchParams(search).get("sourceBinding")}</output>;
}

function renderLauncher() {
  render(
    <MemoryRouter>
      <Routes>
        <Route
          path="/"
          element={<SourceMappingLauncher sourceId={sourceId} deviceId="device-1" />}
        />
        <Route path="/ai-agent" element={<Destination />} />
      </Routes>
    </MemoryRouter>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

it("opens the assistant with the revision read from the selected active source", async () => {
  const fetch = vi
    .spyOn(globalThis, "fetch")
    .mockResolvedValueOnce(Response.json({ sourceMappingEnabled: true }))
    .mockResolvedValueOnce(
      Response.json({
        id: sourceId,
        deviceId: "device-1",
        sourceActive: true,
        schemaRevision: 4,
        currentSchemaRevision: 4,
      }),
    );
  renderLauncher();
  await userEvent.click(screen.getByRole("button", { name: "Подготовить маппинг" }));
  expect(await screen.findByText(`${sourceId}:4`)).toBeInTheDocument();
  expect(fetch).toHaveBeenCalledTimes(2);
});

it("keeps the user on the source page when mapping is disabled", async () => {
  const fetch = vi
    .spyOn(globalThis, "fetch")
    .mockResolvedValueOnce(Response.json({ sourceMappingEnabled: false }));
  renderLauncher();
  await userEvent.click(screen.getByRole("button", { name: "Подготовить маппинг" }));
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Подготовка маппинга в ассистенте отключена.",
  );
  expect(fetch).toHaveBeenCalledOnce();
});
