import type { RuleCompilation, RuleCutoverIntent } from "./configurableRuleApi";

export type RuleActivationContext = { account: string; ruleId: string; grantId: string; compilation: RuleCompilation };
export type RuleActivationRecovery = { context: RuleActivationContext; intent: RuleCutoverIntent; dispatched: boolean };

const storageKey = (actorId: string) => `voltcanary.rule-activation.v1.${actorId}`;

export function readRuleActivation(actorId: string): RuleActivationRecovery | null {
  try {
    const recovery = JSON.parse(sessionStorage.getItem(storageKey(actorId)) ?? "null") as RuleActivationRecovery | null;
    if (!recovery?.context?.compilation?.checkpoint?.digest || !recovery.intent?.successorEpoch
      || recovery.intent.ruleId !== recovery.context.ruleId || recovery.intent.grantId !== recovery.context.grantId
      || recovery.intent.bundleId !== recovery.context.compilation.bundleId || recovery.intent.bundleDigest !== recovery.context.compilation.bundleDigest
      || recovery.intent.checkpointDigest !== recovery.context.compilation.checkpoint.digest || typeof recovery.dispatched !== "boolean") return null;
    return recovery;
  } catch {
    return null;
  }
}

export function saveRuleActivation(actorId: string, recovery: RuleActivationRecovery | null) {
  try {
    if (recovery) sessionStorage.setItem(storageKey(actorId), JSON.stringify(recovery));
    else sessionStorage.removeItem(storageKey(actorId));
  } catch {
    throw new Error("This browser could not save the activation for recovery.");
  }
}
