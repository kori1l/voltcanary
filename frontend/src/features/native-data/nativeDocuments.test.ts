import { expect, it } from "vitest";
import { collectionDocument, mutationDocument, literal, publishedCreationTypes, newField } from "./nativeDocuments";
import type { Dataset, Review } from "./nativeDataApi";
import nativeAuthoring from "../../../../backend/common/common-vcl/src/test/resources/native-authoring/native-table-authoring.json";

const reserved: Review = { reviewId: "review", resourceId: "table", mutationId: "mutation", fieldIdsByKey: { name: "field-a", amount: "field-b" },
  revision: 1, reviewDigest: null, semanticDigest: null, status: "RESERVED", expiresAt: "2099-11-03T00:00:00Z", receiptId: "receipt", outcomeCode: null, typedDiff: null };
const dataset: Dataset = { id: "table", schemaRevision: 1, generation: "generation", dataVersion: 7, liveQueryReady: true,
  definition: { label: "Orders", fields: [{ id: "field-a", label: "Name", type: "TEXT", nullable: false }, { id: "field-b", label: "Amount", type: "DECIMAL", nullable: true, unit: "V" }], keyFieldIds: [], systemFields: { recordIdFieldId: "system-id" } } };
it("matches the shared UI/AI native authoring contract fixture for all seven supported field types", () => {
  expect(collectionDocument(nativeAuthoring.draft, nativeAuthoring.reservation)).toEqual(nativeAuthoring.document);
});
it("uses reserved field identities, exact declared keys and optional storage features", () => {
  const a = { ...newField("name"), label: "Order", businessKey: true, nullable: true };
  const b = { ...newField("amount"), label: "Amount", type: "DECIMAL", unit: "V", indexed: true };
  expect(collectionDocument({ label: "Orders", fields: [a, b], history: true }, reserved).spec).toMatchObject({
    id: "table", fields: [{ id: "field-a", type: "TEXT", nullable: false }, { id: "field-b", type: "DECIMAL", unit: "V", precision: 18, scale: 3 }],
    keyFieldIds: ["field-a"], storage: { history: "REVISIONS", indexedFieldIds: ["field-b"] },
  });
});
it("keeps INT64/DECIMAL as strings and never rounds them through Number", () => {
  expect(literal({ id: "a", label: "Counter", type: "INT64", nullable: false }, { mode: "VALUE", value: "9223372036854775807" }).value).toBe("9223372036854775807");
  expect(literal(dataset.definition.fields[1], { mode: "VALUE", value: "999999999999999.999" })).toEqual({ type: "DECIMAL", value: "999999999999999.999", unit: "V" });
});
it("preserves omission versus explicit NULL and exact row/generation preconditions", () => {
  const row = { recordId: "row", recordVersion: "9223372036854775806", values: { "field-a": { type: "TEXT", value: "Original" } } };
  const result = mutationDocument(dataset, reserved, "same-key", "UPDATE", { "field-a": { mode: "UNCHANGED", value: "" }, "field-b": { mode: "NULL", value: "" } }, row);
  expect(result.spec).toMatchObject({ reviewId: "review", mutationId: "mutation", idempotencyKey: "same-key", expectedRowVersion: row.recordVersion,
    key: [{ fieldId: "system-id", literal: { type: "REF", value: "row" } }], values: [{ fieldId: "field-b", literal: { type: "NULL", value: null } }],
    targetPrecondition: { mode: "NATIVE", generation: "generation", dataVersion: 7 } });
  const keyed = { ...dataset, definition: { ...dataset.definition, keyFieldIds: ["field-a"] } };
  expect(mutationDocument(keyed, reserved, "same-key", "DELETE", {}, row).spec).toMatchObject({ key: [{ fieldId: "field-a", literal: row.values["field-a"] }], values: [] });
});
it("only offers form renderers whose types are published by the shared registry", () => {
  expect(publishedCreationTypes({ registry: { registryDigest: "digest", $defs: { Literal: { oneOf: [{ properties: { type: { const: "TEXT" } } }, { properties: { type: { const: "REF" } } }] } } }, nativeQuery: { profile: "native-live-sql-1" }, ruleExecutionReady: false })).toEqual(["TEXT"]);
});
