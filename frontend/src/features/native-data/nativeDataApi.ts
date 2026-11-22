import { apiRequest, ApiError } from "../../api/client";

export type ReferenceTarget =
  { kind: "DATASET"; datasetId: string } | { kind: "RESOURCE"; resourceKind: string };
export type Field = {
  id: string;
  label: string;
  type: string;
  nullable: boolean;
  unit?: string;
  values?: string[];
  maxLength?: number;
  precision?: number;
  scale?: number;
  target?: ReferenceTarget;
};
export type Literal = { type: string; value: unknown; unit?: string };
export type Document = { language: "VCL/1"; kind: string; spec: Record<string, unknown> };
export type QueryCapabilities = {
  methods?: string[];
  predicateFieldTypes?: string[];
  equalityOnlyFieldTypes?: string[];
};
export type DatasetCapabilities = {
  profile: string;
  datasetId: string;
  schemaRevision: number;
  generation: string;
  sourceMode: string;
  actions: string[];
  unavailableReasons: Record<string, string>;
};
export type RowFilter = { fieldId: string; method: string; literal?: Literal };
export type Dataset = {
  id: string;
  schemaRevision: number;
  generation: string;
  dataVersion: number;
  readyIndexedFields?: string[];
  queryCapabilities?: QueryCapabilities;
  definition: {
    label: string;
    fields: Field[];
    keyFieldIds: string[];
    systemFields: { recordIdFieldId: string };
  };
  liveQueryReady: boolean;
};
export type Summary = Pick<Dataset, "id" | "schemaRevision"> & { label: string };
export type Row = { recordId: string; recordVersion: string; values: Record<string, Literal> };
export type TemporalSnapshot = { snapshotId: string; expiresAt: string; rowCount: number };
export type TemporalMatch = { status: string; reason: string | null; row: Row | null };
export type TemporalSegment = TemporalMatch & { start: string; end: string };
export type TemporalResult = { readManifest: { dataVersion: number } };
export type Page = {
  rows: Row[];
  nextCursor: string | null;
  completeness: string;
  truncationReason: string | null;
  readManifest: { dataVersion: number };
};
export type HistoryGeneration = {
  generation: string;
  schemaRevision: number;
  status: string;
  firstDataVersion: number;
  lastDataVersion: number;
};
export type HistoricalSchema = {
  recordIdFieldId: string;
  definition: { revision: number; fields: Field[] };
  firstDataVersion: number;
  lastDataVersion: number;
  maxQueryCuts: number;
};
export type SavedImportMapping = {
  format: { kind: "CSV" | "JSONL"; encoding: string; delimiter?: string; header?: string[] };
  columns: {
    sourceColumn: string;
    sourceType: string;
    sourceUnit: string | null;
    targetFieldId: string;
    emptyValue: "REJECT" | "AS_NULL" | "PRESERVE_EMPTY";
    conversions: ({ method: string } & Record<string, unknown>)[];
  }[];
};
export type ImportReview = {
  id: string;
  status: string;
  reviewDigest: string;
  expiresAt: string;
  totalRows: number | null;
  processedRows: number;
  unprocessedRows: number | null;
  validRows: number;
  invalidRows: number;
  validationComplete: boolean;
  displayedErrorsTruncated: boolean;
  errors: { recordOrdinal: number; code: string }[];
  typedDiff: {
    idempotencyKey: string;
    inserts: number;
    updates: number;
    deletes: number;
    mapping?: SavedImportMapping;
    mappingRevision?: number;
    target?: { datasetId: string; schemaRevision: number };
  };
  progress: { committedRows: number } | null;
  outcomeCode: string | null;
};
export type ImportChunk = {
  chunkIndex: number;
  rows: { recordId: string; recordVersion: string }[];
};
export type MigrationReview = {
  id: string;
  status: string;
  reviewRevision: number;
  reviewDigest: string | null;
  idempotencyKey: string;
  expiresAt: string;
  fields: { key: string; id: string }[];
  impact?: {
    operation: string;
    blocked: boolean;
    addedFieldIds: string[];
    removedFieldIds: string[];
    changedFieldIds: string[];
    owners: { owner: string; dependencies: { id: string; kind: string; blocking: boolean }[] }[];
  };
  receipt?: { status?: string; schemaRevision: number };
  outcomeCode: string | null;
};
export type SharingPolicy = {
  readFieldIds: string[];
  writeFieldIds: string[];
  rows: { mode: "ALL" | "IDS"; recordIds: string[] };
  actions: string[];
};
export type SharingDefinition = {
  workspaceId: string;
  subject: { kind: "USER" | "TEAM"; id: string };
  policy: SharingPolicy;
};
export type SharingReview = {
  reviewId: string;
  grantId: string;
  status: string;
  reviewRevision: number;
  reviewDigest: string;
  idempotencyKey: string;
  expiresAt: string;
  definition: SharingDefinition;
};
export type SharingGrant = {
  grantId: string;
  workspaceId: string;
  subjectKind: string;
  subjectId: string;
  revision: number;
  policy: SharingPolicy;
};
export type SharedTable = { accountId: string; datasetId: string; grantId: string; label: string };
export type SharedTableContext = { workspace: string; table: SharedTable };
export type SharedDescription = {
  schemaRevision: number;
  fields: Field[];
  recordIdFieldId: string;
  policy: SharingPolicy;
  label: string;
  generation: string;
  dataVersion: number;
  keysVisible: boolean;
  keyFieldIds?: string[];
  readyIndexedFields?: string[];
  queryCapabilities?: QueryCapabilities;
};
export type Review = {
  reviewId: string;
  resourceId: string;
  mutationId?: string;
  createdRecordId?: string;
  fieldIdsByKey?: Record<string, string>;
  revision: number;
  reviewDigest: string | null;
  semanticDigest: string | null;
  status: string;
  expiresAt: string;
  receiptId: string;
  outcomeCode: string | null;
  typedDiff: Record<string, unknown> | null;
  result?: unknown;
};
export type Language = {
  registry: { registryDigest: string; $defs: Record<string, unknown> };
  nativeQuery: { profile: string };
  ruleExecutionReady: boolean;
};
const post = <T>(path: string, body: unknown) =>
  apiRequest<T>(path, { method: "POST", body: JSON.stringify(body) });
export const nativeDataApi = {
  list: (signal?: AbortSignal) => apiRequest<Summary[]>("/data/datasets", { signal }),
  grants: (id: string, signal?: AbortSignal) =>
    apiRequest<SharingGrant[]>(`${datasetPath(id)}/sharing`, { signal }),
  prepareSharing: (id: string, definition: SharingDefinition, idempotencyKey: string) =>
    post<SharingReview>(sharingPath(id), { definition, idempotencyKey }),
  inspectSharing: (id: string, review: string, signal?: AbortSignal) =>
    apiRequest<SharingReview>(sharingPath(id, review), { signal }),
  applySharing: (id: string, review: SharingReview) =>
    post<SharingReview>(`${sharingPath(id, review.reviewId)}/apply`, {
      reviewRevision: review.reviewRevision,
      reviewDigest: review.reviewDigest,
      idempotencyKey: review.idempotencyKey,
    }),
  revokeSharing: (id: string, grant: SharingGrant, key: string) =>
    post<unknown>(`${datasetPath(id)}/sharing/${encodeURIComponent(grant.grantId)}/revoke`, {
      expectedRevision: grant.revision,
      idempotencyKey: key,
    }),
  archived: (signal?: AbortSignal) => apiRequest<Summary[]>("/data/datasets/archived", { signal }),
  sharedTables: (workspace: string, after: string | null, signal?: AbortSignal) =>
    apiRequest<{ items: SharedTable[]; nextAfterGrant: string | null }>(
      `${workspacePath(workspace)}/datasets${after ? "?afterGrant=" + encodeURIComponent(after) : ""}`,
      { signal },
    ),
  describeShared: (workspace: string, table: SharedTable, signal?: AbortSignal) =>
    apiRequest<SharedDescription>(sharedPath(workspace, table), { signal }),
  reserveSharedRow: (
    workspace: string,
    table: SharedTable,
    schemaRevision: number,
    idempotencyKey: string,
  ) =>
    post<Review>(sharedPath(workspace, table, "/mutation-reservations"), {
      schemaRevision,
      idempotencyKey,
    }),
  prepareSharedRow: (
    workspace: string,
    table: SharedTable,
    reservationId: string,
    document: Document,
  ) => post<Review>(sharedPath(workspace, table, "/mutation-reviews"), { reservationId, document }),
  inspectSharedRow: (workspace: string, table: SharedTable, review: string, signal?: AbortSignal) =>
    apiRequest<Review>(sharedPath(workspace, table, "/reviews/" + encodeURIComponent(review)), {
      signal,
    }),
  applySharedRow: (workspace: string, table: SharedTable, review: Review, idempotencyKey: string) =>
    post<Review>(
      sharedPath(workspace, table, "/reviews/" + encodeURIComponent(review.reviewId) + "/apply"),
      {
        reviewRevision: review.revision,
        reviewDigest: review.reviewDigest,
        idempotencyKey,
      },
    ),
  queryShared: (
    workspace: string,
    table: SharedTable,
    description: SharedDescription,
    cursor: string | null,
    signal?: AbortSignal,
    filter?: RowFilter | null,
  ) => {
    const document: Document = {
      language: "VCL/1",
      kind: "QUERY",
      spec: {
        target: { datasetId: table.datasetId, schemaRevision: description.schemaRevision },
        select: description.fields.map((field) => field.id),
        where: queryPredicate(filter),
        order: [{ fieldId: description.recordIdFieldId, direction: "ASC" }],
        limit: 25,
        cursor,
        read: { mode: "LIVE" },
      },
    };
    return apiRequest<Page>(sharedPath(workspace, table, "/query"), {
      method: "POST",
      body: JSON.stringify({ document }),
      signal,
    });
  },
  language: (signal?: AbortSignal) => apiRequest<Language>("/data/language", { signal }),
  describe: (id: string, signal?: AbortSignal) =>
    apiRequest<Dataset>(`/data/datasets/${encodeURIComponent(id)}`, { signal }),
  capabilities: (id: string, signal?: AbortSignal) =>
    apiRequest<DatasetCapabilities>(`/data/datasets/${encodeURIComponent(id)}/capabilities`, {
      signal,
    }),
  reserveCollection: (fieldKeys: string[], key: string) =>
    post<Review>("/data/review-reservations", {
      kind: "COLLECTION",
      fieldKeys,
      idempotencyKey: key,
    }),
  reserveRow: (dataset: Dataset, key: string) =>
    post<Review>("/data/review-reservations", {
      kind: "MUTATION",
      target: target(dataset),
      idempotencyKey: key,
    }),
  prepareCollection: (reservationId: string, document: Document) =>
    post<Review>("/data/dataset-reviews", { reservationId, document }),
  prepareRow: (id: string, reservationId: string, document: Document) =>
    post<Review>(`/data/datasets/${encodeURIComponent(id)}/mutation-reviews`, {
      reservationId,
      document,
    }),
  inspect: (id: string, signal?: AbortSignal) =>
    apiRequest<Review>(`/data/reviews/${encodeURIComponent(id)}`, { signal }),
  reconcile: (id: string) => post<Review>(`/data/reviews/${encodeURIComponent(id)}/reconcile`, {}),
  apply: async (review: Review, key: string) => {
    try {
      return await post<Review>(`/data/reviews/${encodeURIComponent(review.reviewId)}/apply`, {
        reviewRevision: review.revision,
        reviewDigest: review.reviewDigest,
        idempotencyKey: key,
      });
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        const current = await nativeDataApi.inspect(review.reviewId);
        if (current.status === "FAILED") return current;
      }
      throw error;
    }
  },
  query: (
    dataset: Dataset,
    cursor: string | null,
    signal?: AbortSignal,
    filter?: RowFilter | null,
  ) => postQuery(dataset, cursor, signal, filter),
  sealTemporal: (
    dataset: Dataset,
    intervalFieldId: string,
    keyFieldIds: string[],
    shared?: SharedTableContext,
  ) =>
    post<TemporalSnapshot>(tableRoute(dataset.id, "/query-asof-snapshots", shared), {
      document: queryDocument(dataset, null),
      intervalFieldId,
      keyFieldIds,
    }),
  lookupTemporal: (
    dataset: Dataset,
    snapshotId: string,
    intervalFieldId: string,
    keys: { fieldId: string; literal: Literal }[],
    eventTime: string,
    shared?: SharedTableContext,
  ) =>
    post<TemporalMatch & TemporalResult>(tableRoute(dataset.id, "/query-asof", shared), {
      document: queryDocument(dataset, null, snapshotId),
      intervalFieldId,
      keys,
      eventTime,
    }),
  splitTemporal: (
    dataset: Dataset,
    snapshotId: string,
    intervalFieldId: string,
    keys: { fieldId: string; literal: Literal }[],
    start: string,
    end: string,
    shared?: SharedTableContext,
  ) =>
    post<TemporalResult & { segments: TemporalSegment[] }>(
      tableRoute(dataset.id, "/query-asof-split", shared),
      {
        document: queryDocument(dataset, null, snapshotId),
        intervalFieldId,
        keys,
        start,
        end,
      },
    ),
  importCapabilities: (signal?: AbortSignal) =>
    apiRequest<{
      modes: string[];
      formats: string[];
      maxSourceBytes: number;
      maxAtomicRows: number;
      maxReplacementRows: number;
      maxChunkedRows: number;
    }>("/data/import-capabilities", { signal }),
  uploadImport: (file: File, key: string) =>
    apiRequest<{ id: string; sha256: string }>("/data/import-artifacts", {
      method: "POST",
      body: file,
      headers: { "Content-Type": "application/octet-stream", "Idempotency-Key": key },
    }),
  prepareImport: (id: string, key: string, document: Document) =>
    post<ImportReview>(importPath(id), { idempotencyKey: key, document }),
  inspectImport: (id: string, review: string, signal?: AbortSignal) =>
    apiRequest<ImportReview>(importPath(id, review), { signal }),
  applyImport: (id: string, review: ImportReview) =>
    post<ImportReview>(`${importPath(id, review.id)}/apply`, {
      reviewRevision: 1,
      reviewDigest: review.reviewDigest,
      idempotencyKey: review.typedDiff.idempotencyKey,
    }),
  cancelImport: (job: string) =>
    post<ImportReview>(`/data/jobs/${encodeURIComponent(job)}/cancel`, {}),
  importChunks: (job: string, afterChunk: number) =>
    apiRequest<{ chunks: ImportChunk[]; nextAfter: number; hasMore: boolean }>(
      `/data/jobs/${encodeURIComponent(job)}/chunks?afterChunk=${afterChunk}`,
    ),
  reserveMigration: (id: string, fieldKeys: string[], idempotencyKey: string) =>
    post<MigrationReview>(migrationPath(id), { fieldKeys, idempotencyKey }),
  inspectMigration: (id: string, review: string, signal?: AbortSignal) =>
    apiRequest<MigrationReview>(migrationPath(id, review), { signal }),
  prepareMigration: (id: string, review: string, request: unknown) =>
    post<MigrationReview>(`${migrationPath(id, review)}/prepare`, request),
  applyMigration: (id: string, review: MigrationReview) =>
    post<MigrationReview>(`${migrationPath(id, review.id)}/apply`, {
      reviewRevision: review.reviewRevision,
      reviewDigest: review.reviewDigest,
      idempotencyKey: review.idempotencyKey,
    }),
  historyGenerations: (
    id: string,
    after: string | null,
    signal?: AbortSignal,
    shared?: SharedTableContext,
  ) =>
    apiRequest<{ items: HistoryGeneration[]; nextAfterGeneration: string | null }>(
      tableRoute(id, "/history/generations", shared) +
        (shared ? "&" : "?") +
        new URLSearchParams({ limit: "25", ...(after ? { afterGeneration: after } : {}) }),
      { signal },
    ),
  historySchema: (
    id: string,
    generation: string,
    signal?: AbortSignal,
    shared?: SharedTableContext,
  ) =>
    apiRequest<HistoricalSchema>(
      tableRoute(id, `/history/generations/${encodeURIComponent(generation)}/schema`, shared),
      { signal },
    ),
  historyPage: (
    id: string,
    generation: string,
    throughDataVersion: number,
    schema: HistoricalSchema,
    cursor: string | null,
    signal?: AbortSignal,
    shared?: SharedTableContext,
  ) => {
    const document: Document = {
      language: "VCL/1",
      kind: "QUERY",
      spec: {
        target: { datasetId: id, schemaRevision: schema.definition.revision },
        select: schema.definition.fields.map((field) => field.id),
        where: { op: "CONST", literal: { type: "BOOLEAN", value: true } },
        order: [{ fieldId: schema.recordIdFieldId, direction: "ASC" }],
        limit: 25,
        cursor,
        read: { mode: "LIVE" },
      },
    };
    return apiRequest<Page>(tableRoute(id, "/history/query", shared), {
      method: "POST",
      body: JSON.stringify({ generation, throughDataVersion, document }),
      signal,
    });
  },
};
const datasetPath = (id: string) => `/data/datasets/${encodeURIComponent(id)}`;
const workspacePath = (workspace: string) => `/data/workspaces/${encodeURIComponent(workspace)}`;
const sharedPath = (workspace: string, table: SharedTable, suffix = "") =>
  `${workspacePath(workspace)}/accounts/${encodeURIComponent(table.accountId)}/datasets/${encodeURIComponent(table.datasetId)}${suffix}?grantId=${encodeURIComponent(table.grantId)}`;
const tableRoute = (id: string, suffix: string, context?: SharedTableContext) =>
  context ? sharedPath(context.workspace, context.table, suffix) : datasetPath(id) + suffix;
const sharingPath = (id: string, review?: string) =>
  `${datasetPath(id)}/sharing-reviews${review ? "/" + encodeURIComponent(review) : ""}`;
const importPath = (id: string, review?: string) =>
  `/data/datasets/${encodeURIComponent(id)}/import-reviews${review ? "/" + encodeURIComponent(review) : ""}`;
const migrationPath = (id: string, review?: string) =>
  `/data/datasets/${encodeURIComponent(id)}/migration-reviews${review ? "/" + encodeURIComponent(review) : ""}`;
export const target = (dataset: Dataset) => ({
  datasetId: dataset.id,
  schemaRevision: dataset.schemaRevision,
});
function postQuery(
  dataset: Dataset,
  cursor: string | null,
  signal?: AbortSignal,
  filter?: RowFilter | null,
) {
  return apiRequest<Page>(`${datasetPath(dataset.id)}/query`, {
    method: "POST",
    body: JSON.stringify({ document: queryDocument(dataset, cursor, undefined, filter) }),
    signal,
  });
}
function queryDocument(
  dataset: Dataset,
  cursor: string | null,
  snapshotId?: string,
  filter?: RowFilter | null,
): Document {
  return {
    language: "VCL/1",
    kind: "QUERY",
    spec: {
      target: target(dataset),
      select: dataset.definition.fields.map((field) => field.id),
      where: queryPredicate(filter),
      order: [{ fieldId: dataset.definition.systemFields.recordIdFieldId, direction: "ASC" }],
      limit: 25,
      cursor,
      read: snapshotId ? { mode: "SNAPSHOT", snapshotId } : { mode: "LIVE" },
    },
  };
}
function queryPredicate(filter?: RowFilter | null) {
  if (!filter) return { op: "CONST", literal: { type: "BOOLEAN", value: true } };
  const field = { op: "FIELD", fieldId: filter.fieldId };
  return {
    op: "CALL",
    method: filter.method,
    args: filter.literal ? [field, { op: "CONST", literal: filter.literal }] : [field],
  };
}
