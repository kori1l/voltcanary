import { useEffect, useState } from "react";
import { nativeDataApi, type Dataset, type Literal, type Review } from "./nativeDataApi";
import { displayLiteral } from "./nativeDocuments";
import { NativeCollectionPreview, readNativeCollectionDefinition } from "./NativeCollectionPreview";

export function NativeReviewPanel({
  review,
  busy,
  uncertain,
  onApply,
  onInspect,
  onClose,
  scopedDataset,
}: {
  review: Review;
  busy: boolean;
  uncertain: boolean;
  onApply: () => void;
  onInspect: () => void;
  onClose: () => void;
  scopedDataset?: Dataset;
}) {
  const [loadedDataset, setDataset] = useState<Dataset | null>(null);
  const dataset = scopedDataset ?? loadedDataset;
  const [clock, setClock] = useState(Date.now());
  const operation = review.typedDiff?.operation as string | undefined;
  useEffect(() => {
    setDataset(null);
    if (!operation || scopedDataset) return;
    const controller = new AbortController();
    void nativeDataApi
      .describe(review.resourceId, controller.signal)
      .then(setDataset)
      .catch(() => {});
    return () => controller.abort();
  }, [review.resourceId, operation, scopedDataset]);
  useEffect(() => {
    const timer = window.setInterval(() => setClock(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);
  const dispatched = uncertain || ["APPLYING", "UNKNOWN"].includes(review.status);
  const expired = clock >= Date.parse(review.expiresAt);
  const collection = operation ? null : readNativeCollectionDefinition(review.typedDiff);
  const pairs = [
    ...((review.typedDiff?.key ?? []) as { fieldId: string; literal: Literal }[]),
    ...((review.typedDiff?.values ?? []) as { fieldId: string; literal: Literal }[]),
  ];
  return (
    <section className="panel native-review" aria-label="Prepared review">
      <h2>
        {operation
          ? `${operation === "INSERT" ? "Add" : operation === "UPDATE" ? "Update" : "Delete"} row`
          : "Create table"}{" "}
        · Review {review.revision}
      </h2>
      <p role="status">
        {dispatched
          ? "Outcome unconfirmed. Inspect the existing operation before continuing."
          : review.status === "SUCCEEDED"
            ? "Saved successfully."
            : review.status === "FAILED"
              ? "The operation failed."
              : review.status}
      </p>
      {review.outcomeCode ? <p role="alert">{review.outcomeCode}</p> : null}
      {collection ? (
        <NativeCollectionPreview definition={collection} />
      ) : !operation && review.typedDiff ? (
        <p role="alert">Table preview unavailable. Inspect the saved review.</p>
      ) : null}
      {operation ? (
        <>
          <h3>{dataset?.definition.label ?? "Loading table…"}</h3>
          <dl>
            {pairs.map((pair) => (
              <div key={pair.fieldId}>
                <dt>
                  {dataset?.definition.fields.find((field) => field.id === pair.fieldId)?.label ??
                    "Row identity"}
                </dt>
                <dd>{displayLiteral(pair.literal)}</dd>
              </div>
            ))}
          </dl>
          {operation === "DELETE" ? <p>This removes the selected row.</p> : null}
        </>
      ) : null}
      {operation === "UPDATE" ? (
        <p>Only the listed fields change. Other values remain unchanged.</p>
      ) : null}
      <p>
        Expires: {new Date(review.expiresAt).toLocaleString()}
        {expired ? " · Expired" : ""}
      </p>
      <div className="action-row">
        {review.status === "PREPARED" && !dispatched ? (
          <button
            type="button"
            className="primary-button"
            disabled={
              busy ||
              expired ||
              Boolean(operation && !dataset) ||
              Boolean(!operation && !collection)
            }
            onClick={onApply}
          >
            Confirm and save
          </button>
        ) : null}
        <button type="button" className="ghost-button" disabled={busy} onClick={onInspect}>
          Inspect saved outcome
        </button>
        {!dispatched ? (
          <button type="button" className="ghost-button" disabled={busy} onClick={onClose}>
            {review.status === "SUCCEEDED" ? "Done" : "Close review"}
          </button>
        ) : null}
      </div>
      <details>
        <summary>Review details</summary>
        <dl>
          <dt>Review</dt>
          <dd>{review.reviewId}</dd>
          <dt>Receipt</dt>
          <dd>{review.receiptId}</dd>
          <dt>Digest</dt>
          <dd>{review.reviewDigest ?? "Not prepared"}</dd>
        </dl>
      </details>
    </section>
  );
}
