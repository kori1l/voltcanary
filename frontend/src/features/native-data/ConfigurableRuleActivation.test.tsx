import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { ConfigurableRuleActivation } from "./ConfigurableRuleActivation";
import { applyRuleCutover, confirmRuleCutover, prepareRuleCutover, readDeployedRuleCheckpoint } from "./configurableRuleApi";
import { readRuleActivation, saveRuleActivation, type RuleActivationRecovery } from "./ruleActivationRecovery";

vi.mock("./configurableRuleApi", () => ({
  prepareRuleCutover: vi.fn(), confirmRuleCutover: vi.fn(), applyRuleCutover: vi.fn(), readDeployedRuleCheckpoint: vi.fn(),
}));
beforeEach(() => { vi.resetAllMocks(); });

it("restores only the same review and waits for an explicit resume after a reload", async () => {
  const context = {
    account: "account", ruleId: "rule", grantId: "grant",
    compilation: { bundleId: "bundle", bundleDigest: "bundle-digest", checkpoint: {
      digest: "checkpoint-digest", documentDigest: "document", compilationDigest: "compilation",
      source: { from: "start", boundary: "boundary", digest: "source" }, references: {},
    } },
  };
  const intent = { ruleId: "rule", grantId: "grant", predecessorEpoch: null, successorEpoch: "original-epoch",
    bundleId: "bundle", bundleDigest: "bundle-digest", checkpointDigest: "checkpoint-digest" };
  const saved: RuleActivationRecovery = { context, intent, dispatched: true };
  saveRuleActivation("actor", saved);
  expect(readRuleActivation("different-actor")).toBeNull();
  const recovery = readRuleActivation("actor")!;
  const review = { id: "original-review", accountId: "account", reviewDigest: "review-digest", intent,
    confirmedAt: "2026-11-18T00:00:00Z", expiresAt: "2099-11-18T00:00:00Z" };
  vi.mocked(prepareRuleCutover).mockResolvedValue(review);
  vi.mocked(applyRuleCutover).mockResolvedValue({ epoch: intent.successorEpoch, rule: intent.ruleId, account: context.account,
    grant: intent.grantId, bundle: intent.bundleId, bundleDigest: intent.bundleDigest, state: "ACTIVE" });

  render(<ConfigurableRuleActivation {...recovery.context} actorId="actor" recovery={recovery} onReviewing={vi.fn()}/>);
  expect(prepareRuleCutover).not.toHaveBeenCalled();
  expect(applyRuleCutover).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole("button", { name: "Retry same review" }));
  const resume = await screen.findByRole("button", { name: "Resume same activation" });
  expect(prepareRuleCutover).toHaveBeenCalledExactlyOnceWith(intent, expect.any(AbortSignal));
  expect(applyRuleCutover).not.toHaveBeenCalled();
  fireEvent.click(resume);
  await screen.findByText("Deployment state: ACTIVE");
  expect(confirmRuleCutover).not.toHaveBeenCalled();
  expect(applyRuleCutover).toHaveBeenCalledExactlyOnceWith(review, context.compilation.checkpoint, expect.any(AbortSignal));
  fireEvent.click(screen.getByRole("button", { name: "Close receipt" }));
  expect(readRuleActivation("actor")).toBeNull();
});

it("checks the original deployment without reopening or dispatching an expired review", async () => {
  const compilation = { bundleId: "bundle", bundleDigest: "bundle-digest", checkpoint: {
    digest: "checkpoint-digest", documentDigest: "document", compilationDigest: "compilation",
    source: { from: "start", boundary: "boundary", digest: "source" }, references: {},
  } };
  const context = { account: "account", ruleId: "rule", grantId: "grant", compilation };
  const intent = { ruleId: "rule", grantId: "grant", predecessorEpoch: null, successorEpoch: "original-epoch",
    bundleId: compilation.bundleId, bundleDigest: compilation.bundleDigest, checkpointDigest: compilation.checkpoint.digest };
  const recovery = { context, intent, dispatched: true };
  const current = { rule: "rule", epoch: intent.successorEpoch, state: "ACTIVE", compilation };
  vi.mocked(readDeployedRuleCheckpoint)
    .mockResolvedValueOnce({ ...current, compilation: { ...compilation, bundleDigest: "different" } })
    .mockResolvedValueOnce(current);
  render(<ConfigurableRuleActivation {...context} actorId="actor" recovery={recovery} onReviewing={vi.fn()}/>);
  fireEvent.click(screen.getByRole("button", { name: "Inspect deployment outcome" }));
  await screen.findByText("The deployment differs from the saved activation.");
  fireEvent.click(screen.getByRole("button", { name: "Inspect deployment outcome" }));
  await screen.findByText("Deployment state: ACTIVE");
  expect(readDeployedRuleCheckpoint).toHaveBeenLastCalledWith(context.account, intent.successorEpoch, expect.any(AbortSignal));
  expect(prepareRuleCutover).not.toHaveBeenCalled();
  expect(confirmRuleCutover).not.toHaveBeenCalled();
  expect(applyRuleCutover).not.toHaveBeenCalled();
});
