import { cleanup, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, expect, it } from "vitest";
import { DashboardPredictionEvidence } from "./DashboardPredictionEvidence";
import type { DashboardWidget } from "../../../dashboards/dashboardsApi";
const widget = {
  type: "prediction-evidence",
  presentation: { predictionSort: "RISK_DESC" },
} as unknown as DashboardWidget;
const trained = {
  deviceId: "owned",
  modelFamily: "FAILURE_PREDICTION",
  predictionStatus: "TRAINED_MODEL",
  failureRiskScore: 0.72,
  confidence: 0.8,
  horizonDays: 7,
  modelName: "Fridge model",
  modelVersion: "v2",
  measuredAt: "2026-10-10T12:00:00Z",
};
afterEach(cleanup);
it("links the trained state to its exact response model and never guesses identity", () => {
  const id = "946d02ba-1c2a-4706-8d33-ec81a479bf96";
  const view = render(
    <MemoryRouter>
      <DashboardPredictionEvidence
        widget={widget}
        data={{ rows: [{ ...trained, modelVersionId: id }] }}
      />
    </MemoryRouter>,
  );
  expect(screen.getByRole("link", { name: "Trained model" })).toHaveAttribute(
    "href",
    `/settings/ml?tab=models&modelId=${id}&family=FAILURE_PREDICTION`,
  );
  for (const row of [
    { ...trained, modelVersionId: "javascript:bad" },
    { ...trained, modelVersionId: null },
    { ...trained, modelVersionId: id, predictionStatus: "BASELINE_HEURISTIC" },
  ]) {
    view.rerender(
      <MemoryRouter>
        <DashboardPredictionEvidence widget={widget} data={{ rows: [row] }} />
      </MemoryRouter>,
    );
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  }
});
it("shows genuine percentages, horizon and an authorized device link without claiming accuracy", () => {
  render(
    <MemoryRouter>
      <DashboardPredictionEvidence
        widget={widget}
        data={{ devices: [{ id: "owned", name: "Fridge" }], rows: [trained] }}
      />
    </MemoryRouter>,
  );
  expect(screen.getByText("72%")).toBeVisible();
  expect(screen.getByText("80%")).toBeVisible();
  expect(screen.getByText("7 days")).toBeVisible();
  expect(screen.getByText(/confidence is not measured accuracy/)).toBeVisible();
  expect(screen.getByRole("link", { name: "Fridge" })).toHaveAttribute("href", "/devices/owned");
  expect(screen.queryByText("Fridge model · v2")).not.toBeInTheDocument();
});
it("never converts heuristic, invalid or missing risk into a trained probability or zero", () => {
  const { container } = render(
    <MemoryRouter>
      <DashboardPredictionEvidence
        widget={widget}
        data={{
          rows: [
            {
              ...trained,
              predictionStatus: "BASELINE_HEURISTIC",
              fallbackReason: "No deployed model",
            },
            { ...trained, failureRiskScore: Infinity },
            { ...trained, failureRiskScore: null },
            { ...trained, modelFamily: "ANOMALY_DETECTION" },
            { ...trained, predictionStatus: "UNAVAILABLE" },
          ],
        }}
      />
    </MemoryRouter>,
  );
  expect(container.querySelectorAll("meter")).toHaveLength(0);
  expect(screen.queryByRole("link")).not.toBeInTheDocument();
  expect(screen.getByText("No deployed model")).toBeVisible();
  expect(screen.queryByText("0%")).not.toBeInTheDocument();
  expect(screen.queryByText("72%")).not.toBeInTheDocument();
});
it("sorts the bounded page, discloses pagination and honors optional columns including recorded zero", () => {
  render(
    <MemoryRouter>
      <DashboardPredictionEvidence
        widget={{
          ...widget,
          presentation: {
            predictionSort: "RISK_DESC",
            showPredictionModel: true,
            showPredictionTime: true,
          },
        }}
        data={{
          hasNext: true,
          total: 80,
          devices: [
            { id: "owned", name: "Low" },
            { id: "high", name: "High" },
          ],
          rows: [
            { ...trained, failureRiskScore: 0 },
            { ...trained, deviceId: "high", failureRiskScore: 0.9 },
          ],
        }}
      />
    </MemoryRouter>,
  );
  const rows = screen.getAllByRole("row");
  expect(within(rows[1]).getByRole("link", { name: "High" })).toBeVisible();
  expect(screen.getByText(/Showing 2 of 80/)).toBeVisible();
  expect(screen.getByText("0%")).toBeVisible();
  expect(screen.getByRole("columnheader", { name: "Model" })).toBeVisible();
  expect(screen.getByRole("columnheader", { name: "Calculated" })).toBeVisible();
});
