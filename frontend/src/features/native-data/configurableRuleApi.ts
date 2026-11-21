import { apiRequest } from "../../api/client";

export type RuleCheckpoint = {
  digest: string;
  documentDigest: string;
  compilationDigest: string;
  source: { from: string; boundary: string; digest: string };
  references: Record<string, { manifestDigest: string }>;
};

export type RuleCompilation = { bundleId: string; bundleDigest: string; checkpoint: RuleCheckpoint };

export type RuleCutoverIntent = {
  ruleId: string; grantId: string; predecessorEpoch: string | null; successorEpoch: string;
  bundleId: string; bundleDigest: string; checkpointDigest: string;
};
type RuleCutoverReview = {
  id: string; accountId: string; intent: RuleCutoverIntent; reviewDigest: string; expiresAt: string; confirmedAt: string | null;
};
type RuleDeployment = {
  epoch: string; rule: string; account: string; grant: string; bundle: string; bundleDigest: string;
  state: "STAGED" | "ACTIVATING" | "ACTIVE" | "DRAINING" | "PAUSED" | "REVOKED";
};

const cutoverReviewPath = "/alert-rules/configurable/cutover-reviews";
const cutoverPath = "/analytics/configurable/rule-cutovers";

export function readDeployedRuleCheckpoint(account: string, epoch: string, signal: AbortSignal) {
  return apiRequest<{ rule: string; epoch: string; state: string; compilation: RuleCompilation }>(`${cutoverPath}/deployed-checkpoint`, {
    method: "POST", body: JSON.stringify({ account, epoch }), signal,
  });
}

export function prepareRuleCutover(intent: RuleCutoverIntent, signal: AbortSignal) {
  return apiRequest<RuleCutoverReview>(cutoverReviewPath, { method: "POST", body: JSON.stringify(intent), signal });
}

export function confirmRuleCutover(review: RuleCutoverReview, signal: AbortSignal) {
  return apiRequest<RuleCutoverReview>(`${cutoverReviewPath}/${encodeURIComponent(review.id)}/confirm`, {
    method: "POST", body: JSON.stringify({ reviewDigest: review.reviewDigest }), signal,
  });
}

export function applyRuleCutover(review: RuleCutoverReview, checkpoint: RuleCheckpoint, signal: AbortSignal) {
  return apiRequest<RuleDeployment>(`${cutoverPath}/${encodeURIComponent(review.id)}/apply`, {
    method: "POST", body: exactJson(checkpoint), signal,
  });
}

export function compileConfigurableRule(document: unknown, readSet: unknown, signal: AbortSignal) {
  return apiRequest<RuleCompilation>("/analytics/configurable-rules/compilations", {
    method: "POST", body: JSON.stringify({ document, readSet }), signal,
  });
}

type WindowPreview = { sourceCutDigest: string; result: {
  status: string; reason: string | null; literal: unknown; evidence: string[];
  referenceManifestDigests: Record<string, string>;
} };

export type RulePreviewWindow = {
  account: string; node: string; subjectLiteral: string; from: string; until: string;
};

export function previewRuleWindow(compilation: RuleCompilation, window: RulePreviewWindow, signal: AbortSignal) {
  return apiRequest<WindowPreview>("/analytics/configurable/rule-cutovers/window-preview", {
    method: "POST",
    body: exactJson({ ...window, bundle: compilation.bundleId, checkpoint: compilation.checkpoint }),
    signal,
  });
}

type SessionPreview = {
  checkpoint: string; status: string; reason: string | null; literal: unknown;
  sessionWindow: { startInclusive: string; endExclusive: string } | null;
  entry: string | null; exit: string | null; firstWitness: string | null; lastWitness: string | null;
  positionWitnesses: string[]; evidence: string[]; coverageDigest: string | null;
};

export function previewRuleSession(compilation: RuleCompilation, session: RulePreviewWindow & {
  occurrenceLiteral: string; circle: string | null; proof: string | null;
}, signal: AbortSignal) {
  return apiRequest<SessionPreview>(`${cutoverPath}/session-preview`, {
    method: "POST", body: exactJson({ ...session, bundle: compilation.bundleId, checkpoint: compilation.checkpoint }), signal,
  });
}

function exactJson(payload: unknown) {
  return JSON.stringify(payload, (_key, value: unknown) => {
    if (typeof value === "number" && Number.isInteger(value) && !Number.isSafeInteger(value)) {
      throw new Error("The checkpoint contains an integer outside the browser's exact range.");
    }
    return value;
  });
}
