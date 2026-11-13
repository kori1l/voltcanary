import { apiRequest, ApiError } from "../../api/client";

export type Field = { id: string; label: string; type: string; nullable: boolean; unit?: string; values?: string[]; maxLength?: number; precision?: number; scale?: number };
export type Literal = { type: string; value: unknown; unit?: string };
export type Document = { language: "VCL/1"; kind: string; spec: Record<string, unknown> };
export type Dataset = { id: string; schemaRevision: number; generation: string; dataVersion: number;
  definition: { label: string; fields: Field[]; keyFieldIds: string[]; systemFields: { recordIdFieldId: string } }; liveQueryReady: boolean };
export type Summary = Pick<Dataset, "id" | "schemaRevision"> & { label: string };
export type Row = { recordId: string; recordVersion: string; values: Record<string, Literal> };
export type Page = { rows: Row[]; nextCursor: string | null; completeness: string; truncationReason: string | null; readManifest: { dataVersion: number } };
export type Review = { reviewId: string; resourceId: string; mutationId?: string; createdRecordId?: string;
  fieldIdsByKey?: Record<string, string>; revision: number; reviewDigest: string | null; semanticDigest: string | null;
  status: string; expiresAt: string; receiptId: string; outcomeCode: string | null; typedDiff: Record<string, unknown> | null; result?: unknown };
export type Language = { registry: { registryDigest: string; $defs: Record<string, unknown> }; nativeQuery: { profile: string }; ruleExecutionReady: boolean };
const post = <T,>(path: string, body: unknown) => apiRequest<T>(path, { method: "POST", body: JSON.stringify(body) });
export const nativeDataApi = {
  list: (signal?: AbortSignal) => apiRequest<Summary[]>("/data/datasets", { signal }),
  language: (signal?: AbortSignal) => apiRequest<Language>("/data/language", { signal }),
  describe: (id: string, signal?: AbortSignal) => apiRequest<Dataset>(`/data/datasets/${encodeURIComponent(id)}`, { signal }),
  reserveCollection: (fieldKeys: string[], key: string) => post<Review>("/data/review-reservations", { kind: "COLLECTION", fieldKeys, idempotencyKey: key }),
  reserveRow: (dataset: Dataset, key: string) => post<Review>("/data/review-reservations", { kind: "MUTATION", target: target(dataset), idempotencyKey: key }),
  prepareCollection: (reservationId: string, document: Document) => post<Review>("/data/dataset-reviews", { reservationId, document }),
  prepareRow: (id: string, reservationId: string, document: Document) => post<Review>(`/data/datasets/${encodeURIComponent(id)}/mutation-reviews`, { reservationId, document }),
  inspect: (id: string, signal?: AbortSignal) => apiRequest<Review>(`/data/reviews/${encodeURIComponent(id)}`, { signal }),
  reconcile: (id: string) => post<Review>(`/data/reviews/${encodeURIComponent(id)}/reconcile`, {}),
  apply: async (review: Review, key: string) => {
    try { return await post<Review>(`/data/reviews/${encodeURIComponent(review.reviewId)}/apply`, { reviewRevision: review.revision, reviewDigest: review.reviewDigest, idempotencyKey: key }); }
    catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        const current = await nativeDataApi.inspect(review.reviewId);
        if (current.status === "FAILED") return current;
      }
      throw error;
    }
  },
  query: (dataset: Dataset, cursor: string | null, signal?: AbortSignal) => postQuery(dataset, cursor, signal),
};
export const target = (dataset: Dataset) => ({ datasetId: dataset.id, schemaRevision: dataset.schemaRevision });
function postQuery(dataset: Dataset, cursor: string | null, signal?: AbortSignal) {
  const document: Document = { language: "VCL/1", kind: "QUERY", spec: { target: target(dataset), select: dataset.definition.fields.map(field => field.id),
    where: { op: "CONST", literal: { type: "BOOLEAN", value: true } }, order: [{ fieldId: dataset.definition.systemFields.recordIdFieldId, direction: "ASC" }],
    limit: 25, cursor, read: { mode: "LIVE" } } };
  return apiRequest<Page>(`/data/datasets/${encodeURIComponent(dataset.id)}/query`, { method: "POST", body: JSON.stringify({ document }), signal });
}

