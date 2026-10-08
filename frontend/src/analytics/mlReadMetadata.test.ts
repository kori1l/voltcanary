import { expect, it } from "vitest";
import { readModelMetadata, type MlModelVersion } from "./mlModuleApi";

it("decodes the server's JSON metadata fields and retains canonical training lineage", () => {
  const metadata = readModelMetadata({ id: "model", trainingRunId: "run", compatibleDeviceTypesJson: '["FRIDGE"]', supportedMetricsJson: '["power"]', evaluationMetricsJson: '{"approvalGatePassed":true}' } as unknown as Parameters<typeof readModelMetadata>[0]);
  expect(metadata).toMatchObject({ trainingRunId: "run", compatibleDeviceTypes: ["FRIDGE"], supportedMetrics: ["power"], evaluationMetrics: { approvalGatePassed: true } });
});
it("leaves missing lineage unknown and rejects malformed compatibility metadata", () => {
  const metadata = readModelMetadata({ id: "legacy", compatibleDeviceTypesJson: '{"FRIDGE":true}', evaluationMetricsJson: "invalid" } as unknown as MlModelVersion);
  expect(metadata).toMatchObject({ trainingRunId: null, compatibleDeviceTypes: [], evaluationMetrics: {} });
});
