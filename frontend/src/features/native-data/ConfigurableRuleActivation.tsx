import { useEffect, useRef, useState, type FormEvent } from "react";
import {
  applyRuleCutover,
  confirmRuleCutover,
  prepareRuleCutover,
  readDeployedRuleCheckpoint,
  type RuleCompilation,
  type RuleCutoverIntent,
} from "./configurableRuleApi";
import { RuleDependencyComparison } from "./RuleDependencyComparison";
import { saveRuleActivation, type RuleActivationRecovery } from "./ruleActivationRecovery";

type Review = Awaited<ReturnType<typeof prepareRuleCutover>>;
type Deployment = Awaited<ReturnType<typeof applyRuleCutover>>;
const uuid = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}";
const exactUuid = new RegExp(`^${uuid}$`);

export function ConfigurableRuleActivation({
  compilation,
  account,
  ruleId,
  grantId,
  actorId,
  recovery,
  onReviewing,
  onClose,
}: {
  compilation: RuleCompilation;
  account: string;
  ruleId: string;
  grantId: string;
  actorId: string;
  recovery?: RuleActivationRecovery;
  onClose?: () => void;
  onReviewing: (active: boolean) => void;
}) {
  const [intent, setIntent] = useState<RuleCutoverIntent | null>(recovery?.intent ?? null);
  const [predecessor, setPredecessor] = useState(recovery?.intent.predecessorEpoch ?? "");
  const [baseline, setBaseline] = useState<Awaited<
    ReturnType<typeof readDeployedRuleCheckpoint>
  > | null>(null);
  const [review, setReview] = useState<Review | null>(null);
  const [deployment, setDeployment] = useState<Deployment | null>(null);
  const [observedState, setObservedState] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [unknown, setUnknown] = useState(recovery?.dispatched ?? false);
  const [error, setError] = useState<string | null>(null);
  const request = useRef<AbortController | null>(null);
  useEffect(() => () => request.current?.abort(), []);

  async function run(action: (signal: AbortSignal) => Promise<void>) {
    if (request.current) return;
    const controller = new AbortController();
    request.current = controller;
    setError(null);
    setBusy(true);
    try {
      await action(controller.signal);
    } catch (failure) {
      if (!controller.signal.aborted) setError((failure as Error).message);
    } finally {
      request.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  function prepare(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const savedIntent = intent ?? {
      ruleId,
      grantId,
      predecessorEpoch: predecessor || null,
      successorEpoch: crypto.randomUUID(),
      bundleId: compilation.bundleId,
      bundleDigest: compilation.bundleDigest,
      checkpointDigest: compilation.checkpoint.digest,
    };
    void run(async (signal) => {
      saveRuleActivation(actorId, {
        context: { compilation, account, ruleId, grantId },
        intent: savedIntent,
        dispatched: unknown,
      });
      setIntent(savedIntent);
      onReviewing(true);
      const saved = await prepareRuleCutover(savedIntent, signal);
      const matches = (Object.keys(savedIntent) as (keyof RuleCutoverIntent)[]).every(
        (key) => saved.intent[key] === savedIntent[key],
      );
      if (!matches || saved.accountId !== account)
        throw new Error("The saved review differs from the requested cutover.");
      if (!signal.aborted) setReview(saved);
    });
  }

  function compare() {
    setBaseline(null);
    void run(async (signal) => {
      const result = await readDeployedRuleCheckpoint(account, predecessor, signal);
      if (result.rule !== ruleId || result.epoch !== predecessor)
        throw new Error("This deployment belongs to a different rule.");
      if (!signal.aborted) setBaseline(result);
    });
  }

  function activate() {
    if (!review || busy || deployment?.state === "ACTIVE" || observedState) return;
    void run(async (signal) => {
      saveRuleActivation(actorId, {
        context: { compilation, account, ruleId, grantId },
        intent: review.intent,
        dispatched: true,
      });
      setUnknown(true);
      const confirmed = review.confirmedAt ? review : await confirmRuleCutover(review, signal);
      if (signal.aborted) return;
      if (
        confirmed.id !== review.id ||
        confirmed.reviewDigest !== review.reviewDigest ||
        !confirmed.confirmedAt
      ) {
        throw new Error("The server did not confirm this review.");
      }
      setReview(confirmed);
      const result = await applyRuleCutover(confirmed, compilation.checkpoint, signal);
      if (signal.aborted) return;
      if (
        result.epoch !== review.intent.successorEpoch ||
        result.rule !== ruleId ||
        result.account !== account ||
        result.grant !== grantId ||
        result.bundle !== compilation.bundleId ||
        result.bundleDigest !== compilation.bundleDigest
      ) {
        throw new Error("The returned deployment differs from the confirmed review.");
      }
      setDeployment(result);
      setUnknown(false);
    });
  }

  function inspectActivation() {
    if (!intent) return;
    void run(async (signal) => {
      const result = await readDeployedRuleCheckpoint(account, intent.successorEpoch, signal);
      if (
        result.rule !== intent.ruleId ||
        result.epoch !== intent.successorEpoch ||
        result.compilation.bundleId !== intent.bundleId ||
        result.compilation.bundleDigest !== intent.bundleDigest ||
        result.compilation.checkpoint.digest !== intent.checkpointDigest
      ) {
        throw new Error("The deployment differs from the saved activation.");
      }
      if (!signal.aborted) {
        setObservedState(result.state);
        setUnknown(false);
      }
    });
  }

  function close() {
    try {
      saveRuleActivation(actorId, null);
    } catch (failure) {
      setError((failure as Error).message);
      return;
    }
    setIntent(null);
    setBaseline(null);
    setReview(null);
    setDeployment(null);
    setObservedState(null);
    setUnknown(false);
    setError(null);
    onReviewing(false);
    onClose?.();
  }

  return (
    <section className="native-field" aria-label="Rule activation">
      <h3>Rule activation</h3>
      {!review && !observedState ? (
        <form className="form-stack" onSubmit={prepare}>
          <label>
            Current deployment ID (leave blank for first activation)
            <input
              pattern={uuid}
              value={predecessor}
              disabled={Boolean(intent) || busy}
              onChange={(event) => {
                setPredecessor(event.target.value.trim().toLowerCase());
                setBaseline(null);
              }}
            />
          </label>
          <button type="submit" className="primary-button" disabled={busy}>
            {intent ? "Retry same review" : "Review activation"}
          </button>
        </form>
      ) : review && !observedState ? (
        <>
          <p>Current deployment: {review.intent.predecessorEpoch ?? "First activation"}</p>
          <p>Proposed deployment: {review.intent.successorEpoch}</p>
          <p>Effective from: {compilation.checkpoint.source.boundary}</p>
          <p>Review expires: {review.expiresAt}</p>
          <details>
            <summary>Exact review details</summary>
            <p>Review ID: {review.id}</p>
            <p>Digest: {review.reviewDigest}</p>
          </details>
          {!deployment ||
          deployment.state === "ACTIVATING" ||
          deployment.state === "STAGED" ||
          unknown ? (
            <button
              type="button"
              className="primary-button"
              disabled={busy || Date.parse(review.expiresAt) <= Date.now()}
              onClick={activate}
            >
              {unknown || review.confirmedAt ? "Resume same activation" : "Confirm and activate"}
            </button>
          ) : null}
        </>
      ) : null}
      {exactUuid.test(predecessor) ? (
        <button type="button" className="ghost-button" disabled={busy} onClick={compare}>
          Compare with deployed rule
        </button>
      ) : null}
      {baseline ? (
        <RuleDependencyComparison
          previous={baseline.compilation}
          current={compilation}
          title={`Changes from deployment ${baseline.epoch} (${baseline.state})`}
          open
        />
      ) : null}
      {intent &&
      (unknown || deployment?.state === "ACTIVATING" || deployment?.state === "STAGED") ? (
        <button type="button" className="ghost-button" disabled={busy} onClick={inspectActivation}>
          Inspect deployment outcome
        </button>
      ) : null}
      {observedState || (deployment && !unknown) ? (
        <p role="status">Deployment state: {observedState ?? deployment?.state}</p>
      ) : null}
      {busy ? (
        <p role="status">Waiting for the server…</p>
      ) : unknown ? (
        <p role="status">Activation outcome unknown. Resume using this review.</p>
      ) : null}
      {error ? <p role="alert">{error}</p> : null}
      {intent &&
      !unknown &&
      (observedState || !deployment || !["ACTIVATING", "STAGED"].includes(deployment.state)) ? (
        <button type="button" className="ghost-button" disabled={busy} onClick={close}>
          {deployment || observedState ? "Close receipt" : "Close activation review"}
        </button>
      ) : null}
      {unknown && review && Date.parse(review.expiresAt) <= Date.now() ? (
        <p role="status">This review expired. The activation outcome remains unknown.</p>
      ) : null}
    </section>
  );
}
