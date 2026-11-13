import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { NativeDataWorkbench } from "./NativeDataWorkbench";
import { nativeDataApi, type Dataset, type Page, type Review } from "./nativeDataApi";
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
  vi.mocked(nativeDataApi.language).mockResolvedValue({ registry: { registryDigest: "registry", $defs: { Literal: { oneOf: ["TEXT", "DECIMAL", "INT64"].map(type => ({ properties: { type: { const: type } } })) } } }, nativeQuery: { profile: "native-live-sql-1" }, ruleExecutionReady: false });
  vi.mocked(nativeDataApi.describe).mockResolvedValue(dataset);
  vi.mocked(nativeDataApi.query).mockResolvedValue(page);
  vi.mocked(nativeDataApi.reserveCollection).mockResolvedValue({ ...review, status: "RESERVED", typedDiff: null });
  vi.mocked(nativeDataApi.reserveRow).mockResolvedValue({ ...review, status: "RESERVED", typedDiff: null });
  vi.mocked(nativeDataApi.prepareCollection).mockResolvedValue(review);
  vi.mocked(nativeDataApi.prepareRow).mockImplementation(async (_id, _reservation, document) => ({ ...review, typedDiff: document.spec }));
  vi.mocked(nativeDataApi.apply).mockResolvedValue({ ...review, status: "SUCCEEDED" });
  vi.mocked(nativeDataApi.inspect).mockResolvedValue({ ...review, status: "SUCCEEDED" });
});
function mount(path = "/data") { return render(<MemoryRouter initialEntries={[path]}><NativeDataWorkbench/></MemoryRouter>); }
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

