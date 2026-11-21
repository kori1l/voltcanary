import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { NativeDataWorkbench } from "./NativeDataWorkbench";
import { nativeDataApi, type Dataset, type ImportReview, type MigrationReview, type Page, type Review } from "./nativeDataApi";
import { ApiError } from "../../api/client";
vi.mock("./nativeDataApi", async importOriginal => {
  const original = await importOriginal<typeof import("./nativeDataApi")>();
  return { ...original, nativeDataApi: Object.fromEntries(Object.keys(original.nativeDataApi).map(key => [key, vi.fn()])) };
});
const dataset: Dataset = { id: "table", schemaRevision: 1, generation: "generation", dataVersion: 4, liveQueryReady: true,
  definition: { label: "Customer orders", fields: [{ id: "name", label: "Name", type: "TEXT", nullable: false }, { id: "amount", label: "Amount", type: "DECIMAL", nullable: true, unit: "V" }],
    keyFieldIds: [], systemFields: { recordIdFieldId: "system" } } };
const row = { recordId: "row", recordVersion: "9223372036854775806", values: { name: { type: "TEXT", value: "Original" }, amount: { type: "DECIMAL", value: "999999999999999.999", unit: "V" } } };
const page: Page = { rows: [row], nextCursor: null, completeness: "COMPLETE", truncationReason: null, readManifest: { dataVersion: 4 } };
const review: Review = { reviewId: "review", resourceId: "table", mutationId: "mutation", createdRecordId: "reserved-row", receiptId: "receipt", fieldIdsByKey: { field0: "reserved-field" },
  status: "PREPARED", revision: 2, reviewDigest: "stored-digest", semanticDigest: "semantic", expiresAt: "2099-11-03T12:00:00Z",
  typedDiff: { label: "Orders", fields: [{ id: "reserved-field", label: "Order name", type: "TEXT", nullable: false, maxLength: 120 }], keyFieldIds: [],
    storage: { mode: "NATIVE", history: "NONE", indexedFieldIds: [] } }, outcomeCode: null };
beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(nativeDataApi.list).mockResolvedValue([{ id: "table", label: "Customer orders", schemaRevision: 1 }]);
  vi.mocked(nativeDataApi.language).mockResolvedValue({ registry: { registryDigest: "registry", $defs: { Literal: { oneOf: ["TEXT", "DECIMAL", "INT64", "ENUM"].map(type => ({ properties: { type: { const: type } } })) } } }, nativeQuery: { profile: "native-live-sql-1" }, ruleExecutionReady: false });
  vi.mocked(nativeDataApi.describe).mockResolvedValue(dataset);
  vi.mocked(nativeDataApi.capabilities).mockImplementation(async id => ({
    profile: "dataset-actions-1", datasetId: id, schemaRevision: 1, generation: "generation", sourceMode: "NATIVE",
    actions: ["QUERY", "INSERT", "UPDATE", "DELETE", "IMPORT", "MIGRATE", "SHARE", "DASHBOARD", "HISTORY", "TEMPORAL_QUERY"],
    unavailableReasons: {},
  }));
  vi.mocked(nativeDataApi.query).mockResolvedValue(page);
  vi.mocked(nativeDataApi.reserveCollection).mockResolvedValue({ ...review, status: "RESERVED", typedDiff: null });
  vi.mocked(nativeDataApi.reserveRow).mockResolvedValue({ ...review, status: "RESERVED", typedDiff: null });
  vi.mocked(nativeDataApi.prepareCollection).mockResolvedValue(review);
  vi.mocked(nativeDataApi.prepareRow).mockImplementation(async (_id, _reservation, document) => ({ ...review, typedDiff: document.spec }));
  vi.mocked(nativeDataApi.apply).mockResolvedValue({ ...review, status: "SUCCEEDED" });
  vi.mocked(nativeDataApi.inspect).mockResolvedValue({ ...review, status: "SUCCEEDED" });
});
function mount(path = "/data") { return render(<MemoryRouter initialEntries={[path]}><NativeDataWorkbench/></MemoryRouter>); }

it("keeps owner reads available and explains unavailable edits for a read-only source", async () => {
  vi.mocked(nativeDataApi.capabilities).mockResolvedValue({
    profile: "dataset-actions-1", datasetId: "table", schemaRevision: 1, generation: "generation", sourceMode: "EXTERNAL",
    actions: ["QUERY", "DASHBOARD"], unavailableReasons: { UPDATE: "This connection supports reading only." },
  });
  mount("/data?datasetId=table");
  expect(await screen.findByText("External table")).toBeVisible();
  expect(screen.getByText("Original")).toBeVisible();
  expect(screen.getByText("This connection supports reading only.")).toBeVisible();
  for (const name of ["Add row", "Edit", "Delete", "Import records", "Change schema", "Manage access"]) {
    expect(screen.getByRole("button", { name })).toBeDisabled();
  }
  expect(screen.getByRole("button", { name: "Dashboard from table" })).toBeEnabled();
  expect(nativeDataApi.prepareRow).not.toHaveBeenCalled();
});

it("keeps rows visible when capabilities fail and refreshes the same table to retry", async () => {
  vi.mocked(nativeDataApi.capabilities).mockRejectedValueOnce(new Error("Unavailable"));
  mount("/data?datasetId=table");
  expect(await screen.findByText(/Table capabilities are unavailable/)).toBeVisible();
  expect(screen.getByText("Original")).toBeVisible();
  expect(screen.getByRole("button", { name: "Add row" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "Refresh records" }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Add row" })).toBeEnabled());
});

it("rejects capabilities from a different schema revision before enabling mutations", async () => {
  vi.mocked(nativeDataApi.capabilities).mockResolvedValue({
    profile: "dataset-actions-1", datasetId: "table", schemaRevision: 2, generation: "generation", sourceMode: "NATIVE",
    actions: ["QUERY", "INSERT"], unavailableReasons: {},
  });
  mount("/data?datasetId=table");
  expect(await screen.findByText(/Table capabilities changed/)).toBeVisible();
  expect(screen.getByRole("button", { name: "Add row" })).toBeDisabled();
  expect(screen.getByText("Original")).toBeVisible();
});

const migration: MigrationReview = {
  id: "migration", status: "PREPARED", reviewRevision: 2, reviewDigest: "migration-digest",
  idempotencyKey: "migration-key", expiresAt: "2099-11-03T12:00:00Z", fields: [], outcomeCode: null,
  impact: { operation: "MIGRATE", blocked: false, addedFieldIds: [], changedFieldIds: ["amount"], removedFieldIds: [],
    owners: [{ owner: "dashboards", dependencies: [{ id: "draft-id", kind: "DASHBOARD_DRAFT", blocking: false }] }] },
};

it("shows blocking dependencies in the saved schema impact", async () => {
  vi.mocked(nativeDataApi.inspectMigration).mockResolvedValue({
    ...migration, impact: { ...migration.impact!, blocked: true,
      owners: [{ owner: "analytics", dependencies: [{ id: "rule-id", kind: "RULE_DEPLOYMENT", blocking: true }] }] },
  });
  mount("/data?datasetId=table&migrationReview=migration");
  expect(await screen.findByText(/Blocks this change/)).toHaveTextContent("Deployed rule · rule-id");
  expect(screen.queryByRole("button", { name: "Confirm migration" })).not.toBeInTheDocument();
});

it("requires a new schema review when dependencies change at apply", async () => {
  vi.mocked(nativeDataApi.inspectMigration).mockResolvedValue(migration);
  vi.mocked(nativeDataApi.applyMigration).mockRejectedValue(new ApiError("Validation stale", 409, "VALIDATION_STALE"));
  mount("/data?datasetId=table&migrationReview=migration");
  const confirm = await screen.findByRole("button", { name: "Confirm migration" });
  expect(screen.getByText(/Review before reuse/)).toHaveTextContent("Dashboard draft · draft-id");
  fireEvent.click(confirm);
  expect(await screen.findByText(/Start a new review/)).toBeVisible();
  expect(screen.queryByRole("button", { name: "Confirm migration" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Check saved outcome" })).toBeEnabled();
});

it("authors unique ENUM choices through the ordinary collection review", async () => {
  mount();
  const create = await screen.findByRole("button", { name: "Create table" });
  await waitFor(() => expect(create).toBeEnabled());
  fireEvent.click(create);
  fireEvent.change(screen.getByLabelText("Table name"), { target: { value: "Orders" } });
  fireEvent.change(screen.getByLabelText("Column 1 name"), { target: { value: "Status" } });
  fireEvent.change(screen.getByLabelText("Column 1 type"), { target: { value: "ENUM" } });
  const choices = screen.getByRole("textbox", { name: /Allowed values/ });
  const prepare = screen.getByRole("button", { name: "Review table" });
  fireEvent.change(choices, { target: { value: "New\nNew" } });
  expect(prepare).toBeDisabled();
  fireEvent.change(choices, { target: { value: "New\nIn progress" } });
  fireEvent.click(prepare);
  await waitFor(() => expect(nativeDataApi.prepareCollection).toHaveBeenCalledWith("review", expect.objectContaining({
    spec: expect.objectContaining({ fields: [expect.objectContaining({ type: "ENUM", values: ["New", "In progress"] })] }),
  })));
});

const importReview: ImportReview = {
  id: "import", status: "PREPARED", reviewDigest: "import-digest", expiresAt: "2099-11-03T12:00:00Z",
  totalRows: 3, processedRows: 3, unprocessedRows: 0, validRows: 3, invalidRows: 0, validationComplete: true,
  displayedErrorsTruncated: false, errors: [], typedDiff: { idempotencyKey: "import-key", inserts: 3, updates: 0, deletes: 0 },
  progress: null, outcomeCode: null,
};

it("retries capability discovery and uses the published format, operation and limits", async () => {
  vi.mocked(nativeDataApi.describe).mockResolvedValue({
    ...dataset, definition: { ...dataset.definition, keyFieldIds: ["name"] },
  });
  vi.mocked(nativeDataApi.importCapabilities).mockRejectedValueOnce(new Error("Unavailable")).mockResolvedValue({
    formats: ["JSONL"], modes: ["STAGED_REPLACE"], maxSourceBytes: 64,
    maxAtomicRows: 2, maxReplacementRows: 3, maxChunkedRows: 10,
  });
  vi.mocked(nativeDataApi.uploadImport).mockResolvedValue({ id: "artifact", sha256: "digest" });
  vi.mocked(nativeDataApi.prepareImport).mockResolvedValue(importReview);
  vi.mocked(nativeDataApi.inspectImport).mockResolvedValue(importReview);
  mount("/data?datasetId=table");
  fireEvent.click(await screen.findByRole("button", { name: "Import records" }));
  expect(await screen.findByText("Import capabilities are unavailable.")).toBeVisible();
  expect(screen.queryByRole("button", { name: "Validate file" })).toBeNull();
  fireEvent.click(screen.getByRole("button", { name: "Retry loading import capabilities" }));
  expect(await screen.findByLabelText("Format")).toHaveValue("JSONL");
  expect(screen.getByLabelText("Operation")).toHaveValue("STAGED_REPLACE");
  const file = screen.getByLabelText("File");
  const form = screen.getByRole("form", { name: "Import file" });
  fireEvent.change(file, { target: { files: [new File(["a".repeat(65)], "orders.jsonl")] } });
  fireEvent.submit(form);
  expect(await screen.findByText("The file exceeds the 64 byte limit.")).toBeVisible();
  expect(nativeDataApi.uploadImport).not.toHaveBeenCalled();
  fireEvent.change(file, { target: { files: [new File(['{"Name":"Order"}'], "orders.jsonl")] } });
  fireEvent.submit(form);
  await waitFor(() => expect(nativeDataApi.prepareImport).toHaveBeenCalledWith("table", expect.any(String), expect.objectContaining({
    spec: expect.objectContaining({
      format: { kind: "JSONL", encoding: "UTF-8" },
      mode: "STAGED_REPLACE",
      limits: { maxRows: 3, maxBytes: 64, maxRowBytes: 64 },
    }),
  })));
});

it("renders the saved import mapping from the owner review after reload", async () => {
  vi.mocked(nativeDataApi.inspectImport).mockResolvedValue({ ...importReview, typedDiff: {
    ...importReview.typedDiff, target: { datasetId: "table", schemaRevision: 1 }, mappingRevision: 1,
    mapping: { format: { kind: "CSV", encoding: "UTF-8", delimiter: ";" }, columns: [{
      sourceColumn: "volts", sourceType: "TEXT", sourceUnit: "V", targetFieldId: "amount", emptyValue: "AS_NULL",
      conversions: [{ method: "parse_decimal", decimalSeparator: ",", scale: 3, rounding: "REJECT" }],
    }] },
  } });
  mount("/data?datasetId=table&importReview=import");
  fireEvent.click(await screen.findByText("Saved column mapping"));
  expect(nativeDataApi.importCapabilities).not.toHaveBeenCalled();
  expect(screen.getByText("volts → Amount")).toBeVisible();
  expect(screen.getByText(/Semicolon delimiter/)).toBeVisible();
  expect(screen.getByText(/Empty values: Set to null/)).toBeVisible();
  fireEvent.click(screen.getByText("parse_decimal"));
  expect(screen.getByText(/"rounding": "REJECT"/)).toBeVisible();
});

it("selects an authorized row from the declared reference table by business key", async () => {
  const depotId = "9faada66-899f-41e0-9995-991f2c0b826f";
  const owned = { ...dataset, definition: { ...dataset.definition, fields: [...dataset.definition.fields,
    { id: "depot", label: "Depot", type: "REF", nullable: true, target: { kind: "DATASET" as const, datasetId: "depots" } }] } };
  const depots = { ...dataset, id: "depots", definition: { ...dataset.definition, label: "Depots", keyFieldIds: ["name"] } };
  vi.mocked(nativeDataApi.describe).mockImplementation(async id => id === depots.id ? depots : owned);
  vi.mocked(nativeDataApi.query).mockImplementation(async table => table.id === depots.id
    ? { ...page, rows: [{ ...row, recordId: depotId, values: { name: { type: "TEXT", value: "North depot" } } }] }
    : page);

  mount("/data?datasetId=table");
  await screen.findByText("Original");
  fireEvent.click(screen.getByRole("button", { name: "Edit" }));
  fireEvent.change(screen.getByLabelText("Depot action"), { target: { value: "VALUE" } });
  fireEvent.click(screen.getByRole("button", { name: "Browse records" }));
  fireEvent.click(await screen.findByRole("button", { name: "North depot" }));
  expect(screen.getByLabelText("Depot value")).toHaveValue(depotId);
  fireEvent.click(screen.getByRole("button", { name: "Review row" }));
  await waitFor(() => expect(nativeDataApi.prepareRow).toHaveBeenCalledWith("table", expect.anything(), expect.objectContaining({
    spec: expect.objectContaining({ values: [{ fieldId: "depot", literal: { type: "REF", value: depotId } }] }),
  })));
});

it("recovers an uncertain import from its saved review and refreshes persisted rows", async () => {
  vi.mocked(nativeDataApi.inspectImport).mockResolvedValue(importReview);
  vi.mocked(nativeDataApi.applyImport).mockRejectedValue(new Error("Connection lost"));
  mount("/data?datasetId=table&importReview=import");
  fireEvent.click(await screen.findByRole("button", { name: "Confirm import" }));
  await screen.findByText(/Import outcome unknown/);
  expect(screen.queryByRole("button", { name: "Confirm import" })).not.toBeInTheDocument();
  vi.mocked(nativeDataApi.inspectImport).mockResolvedValue({
    ...importReview, status: "PARTIAL", progress: { committedRows: 2 }, outcomeCode: "TARGET_STALE",
  });
  vi.mocked(nativeDataApi.importChunks).mockResolvedValueOnce({
    chunks: [{ chunkIndex: 0, rows: [{ recordId: "row", recordVersion: "9223372036854775806" }] }],
    nextAfter: 0, hasMore: true,
  }).mockResolvedValueOnce({
    chunks: [{ chunkIndex: 1, rows: [{ recordId: "next-row", recordVersion: "2" }] }],
    nextAfter: 1, hasMore: false,
  });
  fireEvent.click(await screen.findByRole("button", { name: "Check saved outcome" }));
  await screen.findByText("Import partially complete");
  expect(screen.getByText("2 committed · 1 uncommitted")).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Show committed batch receipts" }));
  fireEvent.click(await screen.findByText("Batch 1 · 1 committed"));
  expect(screen.getByText("9223372036854775806")).toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "Next receipt page" }));
  expect(await screen.findByText("Batch 2 · 1 committed")).toBeVisible();
  expect(nativeDataApi.importChunks).toHaveBeenNthCalledWith(2, "import", 0);
  expect(nativeDataApi.applyImport).toHaveBeenCalledExactlyOnceWith("table", importReview);
  vi.mocked(nativeDataApi.query).mockResolvedValue({
    ...page, rows: [{ ...row, values: { ...row.values, name: { type: "TEXT", value: "Imported row" } } }],
  });
  fireEvent.click(screen.getByRole("button", { name: "Back to records" }));
  await screen.findByText("Imported row");
});

it("shows incomplete import validation and prevents confirmation", async () => {
  vi.mocked(nativeDataApi.inspectImport).mockResolvedValue({
    ...importReview, validationComplete: false, totalRows: null, unprocessedRows: null, outcomeCode: "ROW_LIMIT_REACHED",
  });
  mount("/data?datasetId=table&importReview=import");
  await screen.findByText(/Validation incomplete/);
  expect(screen.getByText("Total and remaining record counts are unknown.")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Confirm import" })).not.toBeInTheDocument();
  expect(screen.queryByText(/Reviewed changes/)).not.toBeInTheDocument();
});

it("reserves identities and requires explicit confirmation of the stored collection review", async () => {
  mount();
  const create = await screen.findByRole("button", { name: "Create table" });
  await waitFor(() => expect(create).toBeEnabled()); fireEvent.click(create);
  fireEvent.change(screen.getByLabelText("Table name"), { target: { value: "Orders" } });
  fireEvent.change(screen.getByLabelText("Column 1 name"), { target: { value: "Order name" } });
  fireEvent.click(screen.getByRole("button", { name: "Review table" }));
  await screen.findByRole("button", { name: "Confirm and save" });
  expect(nativeDataApi.apply).not.toHaveBeenCalled();
  expect(nativeDataApi.prepareCollection).toHaveBeenCalledWith("review", expect.objectContaining({ spec: expect.objectContaining({ id: "table", fields: [expect.objectContaining({ id: "reserved-field", label: "Order name" })] }) }));
  fireEvent.click(screen.getByRole("button", { name: "Confirm and save" }));
  await screen.findByText("Saved successfully.");
  expect(nativeDataApi.apply).toHaveBeenCalledWith(review, vi.mocked(nativeDataApi.reserveCollection).mock.calls[0][1]);
});
it("submits NULL separately from unchanged values and recovers a lost acknowledgement without redispatch", async () => {
  mount("/data?datasetId=table");
  await screen.findByText("999999999999999.999 V");
  fireEvent.click(screen.getByRole("button", { name: "Edit" }));
  fireEvent.change(screen.getByLabelText("Amount action"), { target: { value: "NULL" } });
  fireEvent.click(screen.getByRole("button", { name: "Review row" }));
  const confirm = await screen.findByRole("button", { name: "Confirm and save" });
  await waitFor(() => expect(confirm).toBeEnabled());
  expect(nativeDataApi.prepareRow).toHaveBeenCalledWith("table", "review", expect.objectContaining({ spec: expect.objectContaining({ expectedRowVersion: "9223372036854775806",
    key: [{ fieldId: "system", literal: { type: "REF", value: "row" } }], values: [{ fieldId: "amount", literal: { type: "NULL", value: null } }] }) }));
  vi.mocked(nativeDataApi.apply).mockRejectedValueOnce(new ApiError("Response lost", 0));
  fireEvent.click(confirm);
  await screen.findByText("Response lost");
  expect(screen.queryByRole("button", { name: "Confirm and save" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Inspect saved outcome" }));
  await screen.findByText("Saved successfully.");
  expect(nativeDataApi.apply).toHaveBeenCalledTimes(1);
  expect(nativeDataApi.inspect).toHaveBeenCalledWith("review");
});
it("restores UNKNOWN by its original review ID and only reconciles that receipt", async () => {
  vi.mocked(nativeDataApi.inspect).mockResolvedValue({ ...review, status: "UNKNOWN" });
  vi.mocked(nativeDataApi.reconcile).mockResolvedValue({ ...review, status: "UNKNOWN" });
  mount("/data?reviewId=review");
  await screen.findByText(/Outcome unconfirmed/);
  fireEvent.click(screen.getByRole("button", { name: "Inspect saved outcome" }));
  await waitFor(() => expect(nativeDataApi.reconcile).toHaveBeenCalledWith("review"));
  expect(nativeDataApi.apply).not.toHaveBeenCalled();
  expect(nativeDataApi.reserveCollection).not.toHaveBeenCalled();
  expect(screen.queryByText("Saved successfully.")).not.toBeInTheDocument();
});
it("uses only the returned seek cursor and shows changed LIVE traversal as unknown", async () => {
  vi.mocked(nativeDataApi.query).mockResolvedValueOnce({ ...page, nextCursor: "signed-cursor", completeness: "PARTIAL", truncationReason: "PAGE_LIMIT" })
    .mockResolvedValueOnce({ ...page, completeness: "UNKNOWN", truncationReason: "LIVE_DATA_CHANGED" });
  mount("/data?datasetId=table");
  await screen.findByText(/More rows available/);
  fireEvent.click(screen.getByRole("button", { name: "Next page" }));
  await screen.findByText(/Data changed while paging/);
  expect(vi.mocked(nativeDataApi.query).mock.calls[1][1]).toBe("signed-cursor");
  expect(screen.getByText(/Total unknown/)).toBeInTheDocument();
});
it("does not let a late result from another table replace the selected table", async () => {
  vi.mocked(nativeDataApi.list).mockResolvedValue([{ id: "table", label: "Old", schemaRevision: 1 }, { id: "new", label: "New", schemaRevision: 1 }]);
  let resolveOld!: (value: Page) => void;
  vi.mocked(nativeDataApi.query).mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; })).mockResolvedValue({ ...page, rows: [] });
  mount("/data?datasetId=table");
  await waitFor(() => expect(nativeDataApi.query).toHaveBeenCalledTimes(1));
  fireEvent.change(screen.getByLabelText("Table", { exact: true }), { target: { value: "new" } });
  await screen.findByText("No rows on this page.");
  resolveOld(page);
  await waitFor(() => expect(screen.queryByText("Original")).not.toBeInTheDocument());
});
it("shows a typed terminal failure without claiming a save or offering another apply", async () => {
  vi.mocked(nativeDataApi.inspect).mockResolvedValue({ ...review, status: "FAILED", outcomeCode: "TARGET_CONFLICT" });
  mount("/data?reviewId=review");
  await screen.findByText("TARGET_CONFLICT");
  expect(screen.queryByRole("button", { name: "Confirm and save" })).not.toBeInTheDocument();
  expect(screen.queryByText("Saved successfully.")).not.toBeInTheDocument();
});

